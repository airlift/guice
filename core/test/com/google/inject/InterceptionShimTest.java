/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject;

import com.google.inject.matcher.Matchers;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;

import static com.google.inject.Asserts.assertContains;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The interception API survives as a binary-compatibility shim: it compiles and links, and fails
 * injector creation with a clear error. The aopalliance interfaces it needs ship inside Guice.
 */
public class InterceptionShimTest
{
    private static final MethodInterceptor NOOP =
            new MethodInterceptor()
            {
                @Override
                public Object invoke(MethodInvocation invocation)
                        throws Throwable
                {
                    return invocation.proceed();
                }
            };

    @Test
    public void bindInterceptorFailsCreationWithClearError()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                new AbstractModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        bindInterceptor(Matchers.any(), Matchers.any(), NOOP);
                                    }
                                }));
        assertContains(exception.getMessage(), "Method interception is not supported");
    }

    @Test
    public void typeEncounterBindInterceptorFailsCreationWithClearError()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bindListener(
                                                        Matchers.any(),
                                                        new TypeListener()
                                                        {
                                                            @Override
                                                            public <I> void hear(TypeLiteral<I> type, TypeEncounter<I> encounter)
                                                            {
                                                                encounter.bindInterceptor(Matchers.any(), NOOP);
                                                            }
                                                        });
                                                bind(Foo.class);
                                            }
                                        })
                                .getInstance(Foo.class));
        assertContains(exception.getMessage(), "Method interception is not supported");
    }

    @Test
    public void vendoredAopallianceResolvesBinderReflectively()
            throws Exception
    {
        // The ecosystem pattern that motivated the shim: resolving Binder's methods reflectively
        // requires every parameter type, including the vendored MethodInterceptor array type.
        Binder.class.getMethod("install", Module.class);
        Binder.class.getMethod(
                "bindInterceptor",
                com.google.inject.matcher.Matcher.class,
                com.google.inject.matcher.Matcher.class,
                MethodInterceptor[].class);
    }

    static class Foo {}
}
