/**
 * ﻿Copyright 2015-2017 Valery Silaev (http://vsilaev.com)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.

 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.tascalate.async.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.commons.javaflow.api.continuable;

import net.tascalate.async.api.ContextualExecutor;
import net.tascalate.concurrent.CompletablePromise;
import net.tascalate.concurrent.CompletableTask;
import net.tascalate.concurrent.Promise;
import net.tascalate.concurrent.PromiseOrigin;

abstract public class AsyncTask<T> extends AsyncMethod {
    public final Promise<T> promise;
    
    protected AsyncTask(ContextualExecutor contextualExecutor) {
        super(contextualExecutor);
        @SuppressWarnings("unchecked")
        CompletableFuture<T> future = (CompletableFuture<T>)this.future; 
        this.promise = contextualExecutor.interruptible() ?
            // For interruptible executor use AbstractCompletableTask
            CompletableTask
                .asyncOn(contextualExecutor)
                .dependent()
                .thenCombine(future, (a, b) -> b, PromiseOrigin.PARAM_ONLY)
            :
            // For non-interruptible use regular wrapper    
            new CompletablePromise<>(future);
    }
    
    @Override
    protected final @continuable void internalRun() {
        try {
            doRun();
            // ensure that promise is resolved
            $$result$$(null, this);
        } catch (Throwable ex) {
            future.completeExceptionally(ex);
        }
    }
    
    abstract protected @continuable void doRun() throws Throwable;

    protected static <T> Promise<T> $$result$$(final T value, final AsyncTask<T> self) {
        @SuppressWarnings("unchecked")
        CompletableFuture<T> future = (CompletableFuture<T>)self.future; 
        future.complete(value);
        return self.promise;
    }
    
    protected @continuable static <V, T> V $$await$$(final CompletionStage<V> originalAwait, final AsyncTask<T> self) {
        return AsyncMethodExecutor.await(originalAwait);
    }
}
