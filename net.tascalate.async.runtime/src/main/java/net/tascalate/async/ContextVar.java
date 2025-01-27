/**
 * ﻿Copyright 2015-2021 Valery Silaev (http://vsilaev.com)
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

package net.tascalate.async;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public interface ContextVar<T> {
    
    T get();
    
    void set(T value);
    
    default void remove() {
        set(null);
    }
    
    public static <T> ContextVar<T> define(Supplier<? extends T> reader, Consumer<? super T> writer) {
        return define(reader, writer, null);
    }
    
    public static <T> ContextVar<T> define(String name, Supplier<? extends T> reader, Consumer<? super T> writer) {
        return define(name, reader, writer, null);
    }
    
    public static <T> ContextVar<T> define(Supplier<? extends T> reader, Consumer<? super T> writer, Runnable eraser) {
        return define(ContextualRunnable.generateVarName(), reader, writer, eraser);
    }
    
    public static <T> ContextVar<T> define(String name, Supplier<? extends T> reader, Consumer<? super T> writer, Runnable eraser) {
        return new ContextVar<T>() {
            @Override
            public T get() { 
                return reader.get();
            }

            @Override
            public void set(T value) {
                writer.accept(value);
            }

            @Override
            public void remove() {
                if (null != eraser) {
                    eraser.run();
                } else {
                    set(null);
                }
            }
            
            @Override
            public String toString() {
                return String.format("<custom-ctx-var>[%s]", name);
            }
        };
    }

    public static <T> ContextVar<T> from(ThreadLocal<T> tl) {
        return new ContextVar<T>() {
            @Override
            public T get() { 
                return tl.get();
            }

            @Override
            public void set(T value) {
                tl.set(value);
            }

            @Override
            public void remove() {
                tl.remove();
            }
            
            @Override
            public String toString() {
                return String.format("<thread-local-ctx-var>[%s]", tl);
            }
        };
    }    
    
    
    public static Function<Runnable, Runnable> relay(ContextVar<?> contextVar) {
        return ContextualRunnable.relayContextVars(Collections.singletonList(contextVar));
    }
    
    public static Function<Runnable, Runnable> relay(ThreadLocal<?> threadLocal) {
        return relay(ContextVar.from(threadLocal));
    }

    public static Function<Runnable, Runnable> relay(ContextVar<?>... contextVars) {
        return ContextualRunnable.relayContextVars(Arrays.asList(contextVars));
    }
    
    public static Function<Runnable, Runnable> relay(ThreadLocal<?>... threadLocals) {
        return ContextualRunnable.relayContextVars(Arrays.stream(threadLocals).map(ContextVar::from).collect(Collectors.toList()));
    }

    public static Function<Runnable, Runnable> relay1(List<? extends ContextVar<?>> contextVars) {
        return ContextualRunnable.relayContextVars(
            contextVars == null ? Collections.emptyList() : new ArrayList<>(contextVars)
        );
    }
    
    public static Function<Runnable, Runnable> relay2(List<? extends ThreadLocal<?>> threadLocals) {
        return ContextualRunnable.relayContextVars(
            threadLocals == null ? Collections.emptyList() : threadLocals
                .stream()
                .map(tl -> ContextVar.from((ThreadLocal<?>)tl))
                .collect(Collectors.toList())
        );
    }
}
