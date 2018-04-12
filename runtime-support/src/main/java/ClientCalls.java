/*
 * Copyright 2014, gRPC Authors All rights reserved.
 * Modifications 2018, Yaroslav Hryniuk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.*;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;

import javax.annotation.Nullable;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;


public final class ClientCalls {

    private static final Logger logger = Logger.getLogger(ClientCalls.class.getName());

    private ClientCalls() {
    }


    public static <ReqT, RespT> ListenableFuture<RespT> futureUnaryCall(
            ClientCall<ReqT, RespT> call,
            ReqT param,
            Metadata metadata) {
        GrpcFuture<RespT> responseFuture = new GrpcFuture<RespT>(call);
        asyncUnaryRequestCall(call, param, new UnaryStreamToFuture<RespT>(responseFuture), false, metadata);
        return responseFuture;
    }

    public static <ReqT, RespT> RespT blockingUnaryCall(ClientCall<ReqT, RespT> call, ReqT param, Metadata metadata) {
        try {
            return getUnchecked(futureUnaryCall(call, param, metadata));
        } catch (RuntimeException | Error e) {
            throw cancelThrow(call, e);
        }
    }


    public static <ReqT, RespT> RespT blockingUnaryCall(
            Channel channel,
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            ReqT param,
            Metadata metadata) {
        ThreadlessExecutor executor = new ThreadlessExecutor();
        ClientCall<ReqT, RespT> call = channel.newCall(method, callOptions.withExecutor(executor));
        try {
            ListenableFuture<RespT> responseFuture = futureUnaryCall(call, param, metadata);
            while (!responseFuture.isDone()) {
                try {
                    executor.waitAndDrain();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw Status.CANCELLED
                            .withDescription("Call was interrupted")
                            .withCause(e)
                            .asRuntimeException();
                }
            }
            return getUnchecked(responseFuture);
        } catch (RuntimeException e) {
            throw cancelThrow(call, e);
        } catch (Error e) {
            throw cancelThrow(call, e);
        }
    }

    private static <V> V getUnchecked(Future<V> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED
                    .withDescription("Call was interrupted")
                    .withCause(e)
                    .asRuntimeException();
        } catch (ExecutionException e) {
            throw toStatusRuntimeException(e.getCause());
        }
    }

    private static StatusRuntimeException toStatusRuntimeException(Throwable t) {
        Throwable cause = checkNotNull(t, "t");
        while (cause != null) {
            // If we have an embedded status, use it and replace the cause
            if (cause instanceof StatusException) {
                StatusException se = (StatusException) cause;
                return new StatusRuntimeException(se.getStatus(), se.getTrailers());
            } else if (cause instanceof StatusRuntimeException) {
                StatusRuntimeException se = (StatusRuntimeException) cause;
                return new StatusRuntimeException(se.getStatus(), se.getTrailers());
            }
            cause = cause.getCause();
        }
        return Status.UNKNOWN.withDescription("unexpected exception").withCause(t)
                .asRuntimeException();
    }

    private static RuntimeException cancelThrow(ClientCall<?, ?> call, Throwable t) {
        try {
            call.cancel(null, t);
        } catch (Throwable e) {
            assert e instanceof RuntimeException || e instanceof Error;
            logger.log(Level.SEVERE, "RuntimeException encountered while closing call", e);
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        } else if (t instanceof Error) {
            throw (Error) t;
        }
        // should be impossible
        throw new AssertionError(t);
    }

    private static <ReqT, RespT> void asyncUnaryRequestCall(
            ClientCall<ReqT, RespT> call, ReqT param, StreamObserver<RespT> responseObserver,
            boolean streamingResponse,
            Metadata metadata) {
        asyncUnaryRequestCall(
                call,
                param,
                new StreamObserverToCallListenerAdapter<ReqT, RespT>(
                        responseObserver,
                        new CallToStreamObserverAdapter<ReqT>(call),
                        streamingResponse),
                streamingResponse, metadata);
    }

    private static <ReqT, RespT> void asyncUnaryRequestCall(
            ClientCall<ReqT, RespT> call,
            ReqT param,
            ClientCall.Listener<RespT> responseListener,
            boolean streamingResponse,
            Metadata metadata) {
        startCall(call, responseListener, streamingResponse, metadata);
        try {
            call.sendMessage(param);
            call.halfClose();
        } catch (RuntimeException e) {
            throw cancelThrow(call, e);
        } catch (Error e) {
            throw cancelThrow(call, e);
        }
    }


    private static <ReqT, RespT> void startCall(ClientCall<ReqT, RespT> call,
                                                ClientCall.Listener<RespT> responseListener,
                                                boolean streamingResponse,
                                                Metadata metadata) {
        call.start(responseListener, metadata);
        if (streamingResponse) {
            call.request(1);
        } else {
            // Initially ask for two responses from flow-control so that if a misbehaving server sends
            // more than one responses, we can catch it and fail it in the listener.
            call.request(2);
        }
    }

    private static final class GrpcFuture<RespT> extends AbstractFuture<RespT> {
        private final ClientCall<?, RespT> call;

        // Non private to avoid synthetic class
        GrpcFuture(ClientCall<?, RespT> call) {
            this.call = call;
        }

        @Override
        protected void interruptTask() {
            call.cancel("GrpcFuture was cancelled", null);
        }

        @Override
        protected boolean set(@Nullable RespT resp) {
            return super.set(resp);
        }

        @Override
        protected boolean setException(Throwable throwable) {
            return super.setException(throwable);
        }
    }

    private static final class CallToStreamObserverAdapter<T> extends ClientCallStreamObserver<T> {
        private boolean frozen;
        private final ClientCall<T, ?> call;
        private Runnable onReadyHandler;
        private boolean autoFlowControlEnabled = true;

        // Non private to avoid synthetic class
        CallToStreamObserverAdapter(ClientCall<T, ?> call) {
            this.call = call;
        }

        private void freeze() {
            this.frozen = true;
        }

        @Override
        public void onNext(T value) {
            call.sendMessage(value);
        }

        @Override
        public void onError(Throwable t) {
            call.cancel("Cancelled by client with StreamObserver.onError()", t);
        }

        @Override
        public void onCompleted() {
            call.halfClose();
        }

        @Override
        public boolean isReady() {
            return call.isReady();
        }

        @Override
        public void setOnReadyHandler(Runnable onReadyHandler) {
            if (frozen) {
                throw new IllegalStateException("Cannot alter onReadyHandler after call started");
            }
            this.onReadyHandler = onReadyHandler;
        }

        @Override
        public void disableAutoInboundFlowControl() {
            if (frozen) {
                throw new IllegalStateException("Cannot disable auto flow control call started");
            }
            autoFlowControlEnabled = false;
        }

        @Override
        public void request(int count) {
            call.request(count);
        }

        @Override
        public void setMessageCompression(boolean enable) {
            call.setMessageCompression(enable);
        }

        @Override
        public void cancel(@Nullable String message, @Nullable Throwable cause) {
            call.cancel(message, cause);
        }
    }

    private static final class StreamObserverToCallListenerAdapter<ReqT, RespT>
            extends ClientCall.Listener<RespT> {
        private final StreamObserver<RespT> observer;
        private final CallToStreamObserverAdapter<ReqT> adapter;
        private final boolean streamingResponse;
        private boolean firstResponseReceived;

        // Non private to avoid synthetic class
        StreamObserverToCallListenerAdapter(
                StreamObserver<RespT> observer,
                CallToStreamObserverAdapter<ReqT> adapter,
                boolean streamingResponse) {
            this.observer = observer;
            this.streamingResponse = streamingResponse;
            this.adapter = adapter;
            if (observer instanceof ClientResponseObserver) {
                @SuppressWarnings("unchecked")
                ClientResponseObserver<ReqT, RespT> clientResponseObserver =
                        (ClientResponseObserver<ReqT, RespT>) observer;
                clientResponseObserver.beforeStart(adapter);
            }
            adapter.freeze();
        }

        @Override
        public void onHeaders(Metadata headers) {
        }

        @Override
        public void onMessage(RespT message) {
            if (firstResponseReceived && !streamingResponse) {
                throw Status.INTERNAL
                        .withDescription("More than one responses received for unary or client-streaming call")
                        .asRuntimeException();
            }
            firstResponseReceived = true;
            observer.onNext(message);

            if (streamingResponse && adapter.autoFlowControlEnabled) {
                // Request delivery of the next inbound message.
                adapter.request(1);
            }
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            if (status.isOk()) {
                observer.onCompleted();
            } else {
                observer.onError(status.asRuntimeException(trailers));
            }
        }

        @Override
        public void onReady() {
            if (adapter.onReadyHandler != null) {
                adapter.onReadyHandler.run();
            }
        }
    }

    /**
     * Complete a GrpcFuture using {@link StreamObserver} events.
     */
    private static final class UnaryStreamToFuture<RespT> extends ClientCall.Listener<RespT> {
        private final GrpcFuture<RespT> responseFuture;
        private RespT value;

        // Non private to avoid synthetic class
        UnaryStreamToFuture(GrpcFuture<RespT> responseFuture) {
            this.responseFuture = responseFuture;
        }

        @Override
        public void onHeaders(Metadata headers) {
        }

        @Override
        public void onMessage(RespT value) {
            if (this.value != null) {
                throw Status.INTERNAL.withDescription("More than one value received for unary call")
                        .asRuntimeException();
            }
            this.value = value;
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            if (status.isOk()) {
                if (value == null) {
                    // No value received so mark the future as an error
                    responseFuture.setException(
                            Status.INTERNAL.withDescription("No value received for unary call")
                                    .asRuntimeException(trailers));
                }
                responseFuture.set(value);
            } else {
                responseFuture.setException(status.asRuntimeException(trailers));
            }
        }
    }

    private static final class ThreadlessExecutor implements Executor {
        private static final Logger log = Logger.getLogger(ThreadlessExecutor.class.getName());

        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();

        // Non private to avoid synthetic class
        ThreadlessExecutor() {
        }

        /**
         * Waits until there is a Runnable, then executes it and all queued Runnables after it.
         */
        public void waitAndDrain() throws InterruptedException {
            Runnable runnable = queue.take();
            while (runnable != null) {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    log.log(Level.WARNING, "Runnable threw exception", t);
                }
                runnable = queue.poll();
            }
        }

        @Override
        public void execute(Runnable runnable) {
            queue.add(runnable);
        }
    }
}
