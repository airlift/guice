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

import com.google.inject.multibindings.Multibinder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.google.inject.Asserts.assertContains;
import static com.google.inject.Asserts.getDeclaringSourcePart;
import static com.google.inject.Binder.boundFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BoundForTest
{
    private static final Mapper BILLING_MAPPER = new Mapper("billing");
    private static final Mapper GOVERNANCE_MAPPER = new Mapper("governance");
    private static final Mapper DEFAULT_MAPPER = new Mapper("default");

    @Test
    public void testBoundForMatchesReflectiveAnnotation()
            throws Exception
    {
        BoundFor reflective = Annotated.class.getDeclaredField("billing").getAnnotation(BoundFor.class);
        BoundFor created = boundFor(Billing.class);

        assertEquals(reflective, created);
        assertEquals(created, reflective);
        assertEquals(reflective.hashCode(), created.hashCode());
        assertEquals(reflective.toString(), created.toString());
        assertSame(BoundFor.class, created.annotationType());
        assertSame(Billing.class, created.value());

        assertNotEquals(boundFor(Governance.class), created);
        assertNotEquals(boundFor(int[].class), created);
        assertEquals(
                Annotated.class.getDeclaredField("array").getAnnotation(BoundFor.class).toString(),
                boundFor(int[].class).toString());

        class Local {}

        class LocalAnnotated
        {
            @BoundFor(Local.class)
            Object local;
        }

        assertEquals(
                LocalAnnotated.class.getDeclaredField("local").getAnnotation(BoundFor.class).toString(),
                boundFor(Local.class).toString());
    }

    @Test
    public void testBoundForRejectsNull()
    {
        assertThrows(NullPointerException.class, () -> boundFor(null));
    }

    @Test
    public void testInjectByClass()
    {
        Injector injector = Guice.createInjector(new MapperModule());

        Consumer consumer = injector.getInstance(Consumer.class);
        assertSame(BILLING_MAPPER, consumer.billing);
        assertSame(GOVERNANCE_MAPPER, consumer.governance);
        assertSame(GOVERNANCE_MAPPER, consumer.governanceField);
        assertSame(BILLING_MAPPER, injector.getInstance(Key.get(Mapper.class, boundFor(Billing.class))));
    }

    @Test
    public void testProvidesMethod()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Provides
                            @BoundFor(Billing.class)
                            Mapper billingMapper()
                            {
                                return BILLING_MAPPER;
                            }

                            @Provides
                            @BoundFor(Governance.class)
                            Mapper governanceMapper()
                            {
                                return GOVERNANCE_MAPPER;
                            }
                        });

        Consumer consumer = injector.getInstance(Consumer.class);
        assertSame(BILLING_MAPPER, consumer.billing);
        assertSame(GOVERNANCE_MAPPER, consumer.governance);
    }

    @Test
    public void testMultibinder()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                Multibinder.newSetBinder(binder(), String.class, boundFor(Billing.class))
                                        .addBinding()
                                        .toInstance("billing");
                                Multibinder.newSetBinder(binder(), String.class, boundFor(Governance.class))
                                        .addBinding()
                                        .toInstance("governance");
                            }
                        });

        assertEquals(Set.of("billing"), injector.getInstance(Key.get(new TypeLiteral<Set<String>>() {}, boundFor(Billing.class))));
        assertEquals(Set.of("governance"), injector.getInstance(Key.get(new TypeLiteral<Set<String>>() {}, boundFor(Governance.class))));
    }

    @Test
    public void testOnlyExactClassMatches()
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
                                        bind(Mapper.class).annotatedWith(boundFor(Billing.class)).toInstance(BILLING_MAPPER);
                                        bind(GovernanceConsumer.class);
                                    }
                                }));

        assertContains(
                exception.getMessage(),
                "No implementation for BoundForTest$Mapper annotated with @BoundFor(BoundForTest.Governance.class) was bound.",
                "Did you mean?",
                "BoundForTest$Mapper annotated with @BoundFor(BoundForTest.Billing.class)");
    }

    @Test
    public void testAnnotatedWithForClassIsRejected()
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
                                        bind(Mapper.class).annotatedWith(BoundFor.class).toInstance(DEFAULT_MAPPER);
                                        bind(ForgetfulConsumer.class);
                                    }
                                }));

        // the rejected binding doesn't also stop the unqualified Mapper from being created just in time
        assertEquals(1, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "Cannot bind BoundForTest$Mapper annotated with @BoundFor alone, as @BoundFor needs a class. Use"
                        + " Binder.boundFor(SomeClass.class) rather than BoundFor.class.",
                getDeclaringSourcePart(BoundForTest.class));
    }

    @Test
    public void testKeyWithForClassIsRejected()
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
                                        bind(Key.get(Mapper.class, BoundFor.class)).toInstance(DEFAULT_MAPPER);
                                        bindConstant().annotatedWith(BoundFor.class).to("constant");
                                        bind(Key.get(Formatter.class, boundFor(Billing.class)).withoutAttributes())
                                                .toInstance(() -> "formatter");
                                    }
                                }));

        assertEquals(3, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "Cannot bind BoundForTest$Mapper annotated with @BoundFor alone",
                "Cannot bind String annotated with @BoundFor alone",
                "Cannot bind BoundForTest$Formatter annotated with @BoundFor alone");
    }

    @Test
    public void testUnqualifiedInjectionIsNotCreatedJustInTime()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                new MapperModule(),
                                new AbstractModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        bind(ForgetfulConsumer.class);
                                    }
                                }));

        assertEquals(1, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "BoundForTest$Mapper is not explicitly bound, and is not created just in time because it has"
                        + " @BoundFor bindings.",
                "Add the missing @BoundFor qualifier, or bind BoundForTest$Mapper explicitly. Its @BoundFor bindings:",
                "* BoundForTest$Mapper annotated with @BoundFor(BoundForTest.Billing.class)",
                "* BoundForTest$Mapper annotated with @BoundFor(BoundForTest.Governance.class)",
                "at BoundForTest$ForgetfulConsumer.<init>",
                "for 1st parameter mapper");
    }

    @Test
    public void testModuleOrderDoesNotMatter()
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
                                        // provider lookups are resolved as soon as the bindings are processed
                                        getProvider(Mapper.class);
                                        bind(ForgetfulConsumer.class);
                                    }
                                },
                                new MapperModule()));

        assertEquals(2, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "BoundForTest$Mapper is not explicitly bound, and is not created just in time",
                "BoundForTest$Mapper is not explicitly bound, and is not created just in time");
    }

    @Test
    public void testUnqualifiedLookupIsNotCreatedJustInTime()
    {
        Injector injector = Guice.createInjector(new MapperModule());

        ConfigurationException exception = assertThrows(ConfigurationException.class, () -> injector.getInstance(Mapper.class));
        assertContains(exception.getMessage(), "BoundForTest$Mapper is not explicitly bound, and is not created just in time");

        exception = assertThrows(ConfigurationException.class, () -> injector.getProvider(Mapper.class));
        assertContains(exception.getMessage(), "BoundForTest$Mapper is not explicitly bound, and is not created just in time");

        exception = assertThrows(ConfigurationException.class, () -> injector.getBinding(Mapper.class));
        assertContains(exception.getMessage(), "BoundForTest$Mapper is not explicitly bound, and is not created just in time");

        // types without @BoundFor bindings are still created just in time
        assertNotNull(injector.getInstance(Other.class));
    }

    @Test
    public void testUnqualifiedProviderIsNotCreatedJustInTime()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                new MapperModule(),
                                new AbstractModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        bind(ForgetfulProviderConsumer.class);
                                    }
                                }));

        assertContains(
                exception.getMessage(),
                "BoundForTest$Mapper is not explicitly bound, and is not created just in time",
                "at BoundForTest$ForgetfulProviderConsumer.<init>");
    }

    @Test
    public void testImplementedByIsNotUsed()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Formatter.class).annotatedWith(boundFor(Billing.class)).toInstance(() -> "billing");
                            }
                        });

        assertEquals("billing", injector.getInstance(Key.get(Formatter.class, boundFor(Billing.class))).format());
        ConfigurationException exception = assertThrows(ConfigurationException.class, () -> injector.getInstance(Formatter.class));
        assertContains(exception.getMessage(), "BoundForTest$Formatter is not explicitly bound, and is not created just in time");
    }

    @Test
    public void testExplicitUnqualifiedBinding()
    {
        Injector injector =
                Guice.createInjector(
                        new MapperModule(),
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Mapper.class).toInstance(DEFAULT_MAPPER);
                            }
                        });

        assertSame(DEFAULT_MAPPER, injector.getInstance(ForgetfulConsumer.class).mapper);
        assertSame(BILLING_MAPPER, injector.getInstance(Consumer.class).billing);
    }

    @Test
    public void testLinkedToUnqualifiedType()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Mapper.class).annotatedWith(boundFor(Billing.class)).to(Mapper.class).in(Scopes.SINGLETON);
                                bind(Mapper.class).annotatedWith(boundFor(Governance.class)).to(Mapper.class).in(Scopes.SINGLETON);
                            }
                        });

        Consumer consumer = injector.getInstance(Consumer.class);
        assertNotEquals(consumer.billing, consumer.governance);
        assertSame(consumer.billing, injector.getInstance(Key.get(Mapper.class, boundFor(Billing.class))));

        // the just-in-time binding that the links target is not used for unqualified injections
        ConfigurationException exception = assertThrows(ConfigurationException.class, () -> injector.getInstance(Mapper.class));
        assertContains(exception.getMessage(), "BoundForTest$Mapper is not explicitly bound, and is not created just in time");

        // but getBinding() still returns it, as tools that walk the binding graph need
        assertEquals(Key.get(Mapper.class), injector.getBinding(Mapper.class).getKey());
    }

    @Test
    public void testChildInjector()
    {
        Injector parent = Guice.createInjector(new MapperModule());
        Injector child = parent.createChildInjector();

        assertSame(BILLING_MAPPER, child.getInstance(Consumer.class).billing);
        ConfigurationException exception = assertThrows(ConfigurationException.class, () -> child.getInstance(ForgetfulConsumer.class));
        assertContains(exception.getMessage(), "BoundForTest$Mapper is not explicitly bound, and is not created just in time");
    }

    @Test
    public void testChildInjectorWithForBindings()
    {
        Injector parent = Guice.createInjector();
        // the parent has no @BoundFor bindings of Mapper, so it creates one just in time
        assertEquals("default", parent.getInstance(ForgetfulConsumer.class).mapper.name);

        Injector child = parent.createChildInjector(new MapperModule());
        assertSame(BILLING_MAPPER, child.getInstance(Consumer.class).billing);
        ConfigurationException exception = assertThrows(ConfigurationException.class, () -> child.getInstance(Mapper.class));
        assertContains(exception.getMessage(), "BoundForTest$Mapper is not explicitly bound, and is not created just in time");

        assertEquals("default", parent.getInstance(Mapper.class).name);
    }

    @Test
    public void testExposedFromPrivateModule()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                new PrivateModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        bind(Mapper.class).annotatedWith(boundFor(Billing.class)).toInstance(BILLING_MAPPER);
                                        expose(Mapper.class).annotatedWith(boundFor(Billing.class));
                                    }
                                },
                                new AbstractModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        bind(ForgetfulConsumer.class);
                                    }
                                }));

        assertContains(
                exception.getMessage(),
                "BoundForTest$Mapper is not explicitly bound, and is not created just in time",
                "* BoundForTest$Mapper annotated with @BoundFor(BoundForTest.Billing.class)",
                "at BoundForTest$ForgetfulConsumer.<init>");
    }

    @Test
    public void testRequireExplicitBindings()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                new MapperModule(),
                                new AbstractModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        binder().requireExplicitBindings();
                                        bind(ForgetfulConsumer.class);
                                    }
                                }));

        // the @BoundFor message replaces the less specific one about explicit bindings
        assertEquals(1, exception.getErrorMessages().size());
        assertContains(exception.getMessage(), "BoundForTest$Mapper is not explicitly bound, and is not created just in time");
    }

    private static class MapperModule
            extends AbstractModule
    {
        @Override
        protected void configure()
        {
            bind(Mapper.class).annotatedWith(boundFor(Billing.class)).toInstance(BILLING_MAPPER);
            bind(Mapper.class).annotatedWith(boundFor(Governance.class)).toInstance(GOVERNANCE_MAPPER);
        }
    }

    static class Mapper
    {
        final String name;

        Mapper()
        {
            this("default");
        }

        Mapper(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ImplementedBy(DefaultFormatter.class)
    interface Formatter
    {
        String format();
    }

    static class DefaultFormatter
            implements Formatter
    {
        @Override
        public String format()
        {
            return "default";
        }
    }

    static class Billing {}

    static class Governance {}

    static class Annotated
    {
        @BoundFor(Billing.class)
        Object billing;

        @BoundFor(int[].class)
        Object array;
    }

    static class Consumer
    {
        final Mapper billing;
        final Mapper governance;

        @Inject
        @BoundFor(Governance.class)
        Mapper governanceField;

        @Inject
        Consumer(@BoundFor(Billing.class) Mapper billing, @BoundFor(Governance.class) Mapper governance)
        {
            this.billing = billing;
            this.governance = governance;
        }
    }

    static class GovernanceConsumer
    {
        @Inject
        GovernanceConsumer(@BoundFor(Governance.class) Mapper mapper) {}
    }

    static class ForgetfulConsumer
    {
        final Mapper mapper;

        @Inject
        ForgetfulConsumer(Mapper mapper)
        {
            this.mapper = mapper;
        }
    }

    static class ForgetfulProviderConsumer
    {
        @Inject
        ForgetfulProviderConsumer(Provider<Mapper> mapper) {}
    }

    static class Other {}
}
