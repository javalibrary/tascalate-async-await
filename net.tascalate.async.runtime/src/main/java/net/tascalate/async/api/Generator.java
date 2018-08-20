/**
 * ﻿Copyright 2015-2018 Valery Silaev (http://vsilaev.com)
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
package net.tascalate.async.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.tascalate.async.generator.OrderedFuturesGenerator;
import net.tascalate.async.generator.ReadyFirstFuturesGenerator;

import net.tascalate.javaflow.util.Option;
import net.tascalate.javaflow.util.SuspendableProducer;
import net.tascalate.javaflow.util.SuspendableStream;

public interface Generator<T> extends AutoCloseable {
    
    @suspendable CompletionStage<T> next(Object producerParam);
    
    default
    @suspendable CompletionStage<T> next() {
        return next(null);
    }
    
    void close();
    
    default <D> D as(Function<Generator<T>, D> decoratorFactory) {
        return decoratorFactory.apply(this);
    }
    
    default
    SuspendableStream<? extends CompletionStage<T>> stream() {
        return new SuspendableStream<>(new SuspendableProducer<CompletionStage<T>>() {
            @Override
            public Option<CompletionStage<T>> produce() {
                CompletionStage<T> result = Generator.this.next();
                return null != result ? Option.some(result) : Option.none();
            }

            @Override
            public void close() {
                Generator.this.close();
            }
            
        });
    }
    
    @SuppressWarnings("unchecked")
    public static <T> Generator<T> empty() {
        return (Generator<T>)OrderedFuturesGenerator.EMPTY_GENERATOR;
    }

    public static <T> Generator<T> of(T readyValue) {
    	return ordered(Stream.of(readyValue));
    }
    
    @SafeVarargs
    public static <T> Generator<T> of(T... readyValues) {
        return ordered(Stream.of(readyValues));
    }
    
    public static <T> Generator<T> of(CompletionStage<T> pendingValue) {
    	return of(Stream.of(pendingValue));
    }
    
    @SafeVarargs
    public static <T> Generator<T> of(CompletionStage<T>... pendingValues) {
        return of(Stream.of(pendingValues));
    }

    public static <T> Generator<T> of(Iterable<? extends CompletionStage<T>> pendingValues) {
        return new OrderedFuturesGenerator<T>(pendingValues.iterator(), pendingValues);
    }
    
    public static <T> Generator<T> of(Stream<? extends CompletionStage<T>> pendingValues) {
        return new OrderedFuturesGenerator<T>(pendingValues.iterator(), pendingValues);
    }
    
    public static <T> Generator<T> ordered(Iterable<T> readyValues) {
        return ordered(StreamSupport.stream(readyValues.spliterator(), false));
    }
    
    public static <T> Generator<T> ordered(Stream<T> readyValues) {
        return of(readyValues.map(CompletableFuture::completedFuture));
    }

    @SafeVarargs
    public static <T> Generator<T> readyFirst(CompletionStage<T>... pendingValues) {
        return readyFirst(Stream.of(pendingValues));
    }

    public static <T> Generator<T> readyFirst(Iterable<? extends CompletionStage<T>> pendingValues) {
        return ReadyFirstFuturesGenerator.create(pendingValues);
    }
    
    public static <T> Generator<T> readyFirst(Stream<? extends CompletionStage<T>> pendingValues) {
        return ReadyFirstFuturesGenerator.create(pendingValues);
    }
                                                                                 
}
