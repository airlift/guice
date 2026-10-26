/*
 * Copyright (C) 2006 Google Inc.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.spi.Elements;
import com.google.inject.util.Modules;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.inject.Asserts.assertContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BinderAttributesTest
{
    private static class ReadAttribute
            extends AbstractModule
    {
        private final String key;
        private Object value = "not read";

        ReadAttribute(String key)
        {
            this.key = key;
        }

        @Override
        protected void configure()
        {
            value = binder().getAttribute(key);
        }
    }

    @Test
    public void testAttributesAreVisibleToModules()
    {
        Object value = new Object();
        ReadAttribute foo = new ReadAttribute("foo");
        ReadAttribute bar = new ReadAttribute("bar");
        Guice.withAttribute("foo", value).withAttribute("bar", 2).createInjector(foo, bar);
        assertSame(value, foo.value);
        assertEquals(2, bar.value);
    }

    @Test
    public void testMissingAttributeThrows()
    {
        AtomicReference<ConfigurationException> thrown = new AtomicReference<>();
        Guice.withAttribute("bar", "baz")
                .createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                try {
                                    binder().getAttribute("foo");
                                }
                                catch (ConfigurationException e) {
                                    thrown.set(e);
                                }
                            }
                        });
        assertContains(thrown.get().getMessage(), "No attribute \"foo\" was added with Guice.withAttribute()");
    }

    @Test
    public void testDefaultImplementationThrows()
    {
        Binder binder = (Binder) Proxy.newProxyInstance(
                Binder.class.getClassLoader(),
                new Class<?>[] {Binder.class},
                InvocationHandler::invokeDefault);
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> binder.getAttribute("foo"));
        assertContains(e.getMessage(), "No attribute \"foo\" was added with Guice.withAttribute()");
    }

    @Test
    public void testMissingAttributeFailsInjectorCreation()
    {
        CreationException e =
                assertThrows(CreationException.class, () -> Guice.createInjector(new ReadAttribute("foo")));
        assertContains(
                e.getMessage(),
                "No attribute \"foo\" was added with Guice.withAttribute()",
                "at BinderAttributesTest$ReadAttribute.configure");
    }

    @Test
    public void testLaterValueReplacesEarlierValue()
    {
        ReadAttribute foo = new ReadAttribute("foo");
        Guice.withAttribute("foo", "first").withAttribute("foo", "second").createInjector(foo);
        assertEquals("second", foo.value);
    }

    @Test
    public void testBuilderIsImmutable()
    {
        Guice.Builder builder = Guice.withAttribute("foo", "bar");
        builder.withAttribute("baz", "qux");
        assertThrows(CreationException.class, () -> builder.createInjector(new ReadAttribute("baz")));
    }

    @Test
    public void testNullKeyOrValueIsRejected()
    {
        assertThrows(NullPointerException.class, () -> Guice.withAttribute(null, "bar"));
        assertThrows(NullPointerException.class, () -> Guice.withAttribute("foo", null));
        assertThrows(
                NullPointerException.class,
                () -> Guice.withAttribute("foo", "bar").withAttribute("baz", null));
    }

    @Test
    public void testAttributesAreVisibleToDerivedBinders()
    {
        AtomicReference<Object> seenByWithSource = new AtomicReference<>();
        AtomicReference<Object> seenBySkipSources = new AtomicReference<>();
        AtomicReference<Object> seenByPrivateModule = new AtomicReference<>();
        ReadAttribute installed = new ReadAttribute("foo");
        Guice.withAttribute("foo", "bar")
                .createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                seenByWithSource.set(binder().withSource("source").getAttribute("foo"));
                                seenBySkipSources.set(binder().skipSources(getClass()).getAttribute("foo"));
                                install(installed);
                            }
                        },
                        new PrivateModule()
                        {
                            @Override
                            protected void configure()
                            {
                                seenByPrivateModule.set(binder().getAttribute("foo"));
                            }
                        });
        assertEquals("bar", seenByWithSource.get());
        assertEquals("bar", seenBySkipSources.get());
        assertEquals("bar", seenByPrivateModule.get());
        assertEquals("bar", installed.value);
    }

    @Test
    public void testAttributesAreVisibleToOverriddenModules()
    {
        ReadAttribute base = new ReadAttribute("foo");
        ReadAttribute override = new ReadAttribute("foo");
        Guice.withAttribute("foo", "bar").createInjector(Modules.override(base).with(override));
        assertEquals("bar", base.value);
        assertEquals("bar", override.value);
    }

    @Test
    public void testChildInjectorsInheritAttributes()
    {
        Injector parent = Guice.withAttribute("foo", "bar").createInjector();
        ReadAttribute child = new ReadAttribute("foo");
        parent.createChildInjector(child);
        assertEquals("bar", child.value);
    }

    @Test
    public void testAttributesAreVisibleWhenRecordingElements()
    {
        ReadAttribute foo = new ReadAttribute("foo");
        Elements.getElements(Stage.DEVELOPMENT, ImmutableMap.of("foo", "bar"), ImmutableList.of(foo));
        assertEquals("bar", foo.value);
    }
}
