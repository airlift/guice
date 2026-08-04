/*
 * Copyright (C) 2008 Google Inc.
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

package com.google.inject.internal.util;

import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static com.google.inject.Asserts.assertContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author jessewilson@google.com (Jesse Wilson)
 */
public class LineNumbersTest
{
    @Test
    public void testLineNumbers()
    {
        try {
            Guice.createInjector(
                    new AbstractModule()
                    {
                        @Override
                        protected void configure()
                        {
                            bind(A.class);
                        }
                    });
            fail();
        }
        catch (CreationException expected) {
            assertContains(
                    expected.getMessage(),
                    "No implementation for LineNumbersTest$B was bound.",
                    "for 1st parameter b",
                    "at LineNumbersTest$1.configure");
        }
    }

    static class A
    {
        @Inject
        A(B b) {}
    }

    public interface B {}

    static class GeneratingClassLoader
            extends ClassLoader
    {
        static String name = "__generated";

        GeneratingClassLoader()
        {
            super(B.class.getClassLoader());
        }

        Class<?> generate()
        {
            // A public class with an @Inject constructor and no debug info, written with the JDK
            // class-file API.
            java.lang.constant.ClassDesc generated = java.lang.constant.ClassDesc.of(name);
            java.lang.constant.MethodTypeDesc ctorType =
                    java.lang.constant.MethodTypeDesc.ofDescriptor(
                            "(" + B.class.descriptorString() + ")V");
            byte[] buf =
                    java.lang.classfile.ClassFile.of()
                            .build(
                                    generated,
                                    classBuilder -> {
                                        classBuilder.withFlags(Modifier.PUBLIC);
                                        classBuilder.withMethod(
                                                "<init>",
                                                ctorType,
                                                Modifier.PUBLIC,
                                                methodBuilder -> {
                                                    methodBuilder.with(
                                                            java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute.of(
                                                                    java.lang.classfile.Annotation.of(
                                                                            java.lang.constant.ClassDesc.ofDescriptor(
                                                                                    Inject.class.descriptorString()))));
                                                    methodBuilder.withCode(
                                                            code -> code.aload(0)
                                                                    .invokespecial(
                                                                            java.lang.constant.ConstantDescs.CD_Object,
                                                                            "<init>",
                                                                            java.lang.constant.MethodTypeDesc.ofDescriptor("()V"))
                                                                    .return_());
                                                });
                                    });

            return defineClass(name.replace('/', '.'), buf, 0, buf.length);
        }
    }

    @Test
    public void testUnavailableByteCodeShowsUnknownSource()
    {
        try {
            Guice.createInjector(
                    new AbstractModule()
                    {
                        @Override
                        protected void configure()
                        {
                            bind(new GeneratingClassLoader().generate());
                        }
                    });
            fail();
        }
        catch (CreationException expected) {
            assertContains(
                    expected.getMessage(),
                    "No implementation for LineNumbersTest$B was bound.",
                    "for 1st parameter",
                    "at LineNumbersTest$2.configure");
        }
    }

    @Test
    public void testGeneratedClassesCanSucceed()
    {
        final Class<?> generated = new GeneratingClassLoader().generate();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(generated);
                                bind(B.class).toInstance(new B() {});
                            }
                        });
        Object instance = injector.getInstance(generated);
        assertEquals(instance.getClass(), generated);
    }
}
