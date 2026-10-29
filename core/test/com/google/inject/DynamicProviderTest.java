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
import com.google.inject.name.Named;
import com.google.inject.spi.DefaultBindingTargetVisitor;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.Elements;
import com.google.inject.spi.HasDependencies;
import com.google.inject.spi.ProviderInstanceBinding;
import com.google.inject.spi.ProvidesMethodBinding;
import com.google.inject.spi.ProvidesMethodTargetVisitor;
import com.google.inject.util.Modules;
import com.google.inject.util.Types;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.google.inject.Asserts.assertContains;
import static com.google.inject.name.Names.named;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicProviderTest
{
    @Test
    public void testResolvesMethodArguments()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("tangerine");
                                bind(Foo.class).toProvider(new FooProvider());
                            }
                        });

        Foo foo = injector.getInstance(Foo.class);
        assertEquals("tangerine", foo.name);
        assertNotSame(foo.bar, injector.getInstance(Foo.class).bar);
    }

    @Test
    public void testCalledOnEveryProvision()
    {
        CountingProvider provider = new CountingProvider();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Integer.class).toProvider(provider);
                            }
                        });

        assertEquals(1, (int) injector.getInstance(Integer.class));
        assertEquals(2, (int) injector.getInstance(Integer.class));
        assertEquals(3, (int) injector.getInstance(Key.get(Integer.class)));
    }

    @Test
    public void testScoping()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Integer.class).toProvider(new CountingProvider()).in(Singleton.class);
                            }
                        });

        assertEquals(1, (int) injector.getInstance(Integer.class));
        assertEquals(1, (int) injector.getInstance(Integer.class));
    }

    @Test
    public void testAnnotatedKey()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("clementine");
                                bind(Bar.class).annotatedWith(named("bar")).toProvider(new BarProvider());
                            }
                        });

        assertEquals("clementine", injector.getInstance(Key.get(Bar.class, named("bar"))).name);
    }

    @Test
    public void testPrivateProviderMethod()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("satsuma");
                                bind(Bar.class).toProvider(new PrivateBarProvider());
                            }
                        });

        assertEquals("satsuma", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testProviderArgument()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).toInstance("provider");
                                bind(Bar.class).toProvider(new ProviderArgumentProvider());
                            }
                        });

        assertEquals("from provider", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testDependenciesAreExposedToTheSpi()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("pomelo");
                                bind(Foo.class).toProvider(new FooProvider());
                            }
                        });

        ProviderInstanceBinding<?> binding =
                assertInstanceOf(ProviderInstanceBinding.class, injector.getBinding(Foo.class));
        HasDependencies hasDependencies =
                assertInstanceOf(HasDependencies.class, binding.getUserSuppliedProvider());
        assertEquals(
                Set.of(Key.get(String.class, named("name")), Key.get(Bar.class)),
                hasDependencies.getDependencies().stream().map(Dependency::getKey).collect(toSet()));
    }

    @Test
    public void testExposedToTheSpiAsAProvidesMethodBinding()
    {
        BarProvider provider = new BarProvider();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("pomelo");
                                bind(Bar.class).annotatedWith(named("bar")).toProvider(provider);
                            }
                        });

        ProvidesMethodBinding<?> binding =
                injector.getBinding(Key.get(Bar.class, named("bar"))).acceptTargetVisitor(new ProvidesMethodVisitor());
        assertEquals(Key.get(Bar.class, named("bar")), binding.getKey());
        assertSame(provider, binding.getEnclosingInstance());
        assertEquals("create", binding.getMethod().getName());
    }

    @Test
    public void testMissingDependencyFailsAtInjectorCreation()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Foo.class).toProvider(new FooProvider());
                                            }
                                        }));

        assertContains(
                exception.getMessage(), "No implementation for String annotated with @Named(\"name\")");
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
                                bind(String.class).annotatedWith(named("name")).toInstance("yuzu");
                                Multibinder.newSetBinder(binder(), Bar.class)
                                        .addBinding()
                                        .toProvider(new BarProvider());
                            }
                        });

        Set<Bar> bars = injector.getInstance(new Key<Set<Bar>>() {});
        assertEquals(1, bars.size());
        assertEquals("yuzu", bars.iterator().next().name);
    }

    @Test
    public void testExceptionInMethod()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Bar.class).toProvider(new ThrowingProvider());
                            }
                        });

        ProvisionException exception =
                assertThrows(ProvisionException.class, () -> injector.getInstance(Bar.class));
        assertContains(exception.getMessage(), "ErrorInCustomProvider", "boom");
    }

    @Test
    public void testNullIsRejectedForNonNullableDependency()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Bar.class).toProvider(new NullProvider());
                            }
                        });

        ProvisionException exception =
                assertThrows(ProvisionException.class, () -> injector.getInstance(BarHolder.class));
        assertContains(exception.getMessage(), "null returned by binding at", "is not @Nullable");
        assertNull(injector.getInstance(NullableBarHolder.class).bar);
    }

    @Test
    public void testGetIsUnsupported()
    {
        UnsupportedOperationException exception =
                assertThrows(UnsupportedOperationException.class, () -> new BarProvider().get());
        assertContains(
                exception.getMessage(),
                BarProvider.class.getName(),
                "bind it with toProvider() or @ProvidedBy");
    }

    @Test
    public void testImplementedGetIsRejected()
    {
        assertContains(
                creationErrorFor(new GetImplementedProvider()),
                "implements get(), which is never called for a DynamicProvider");
    }

    @Test
    public void testGetImplementedToThrowSomethingElseIsRejected()
    {
        assertContains(
                creationErrorFor(new ThrowingGetProvider()),
                "implements get(), which is never called for a DynamicProvider");
    }

    @Test
    public void testGetInheritedFromAnInterfaceIsRejected()
    {
        assertContains(
                creationErrorFor(new InheritedGetProvider()),
                "implements get(), which is never called for a DynamicProvider");
    }

    @Test
    public void testGetOverriddenToThrowUnsupportedIsRejected()
    {
        assertContains(
                creationErrorFor(new UnsupportedGetProvider()),
                "implements get(), which is never called for a DynamicProvider");
    }

    @Test
    public void testImplementedGetIsRejectedWhenBoundByType()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Bar.class).toProvider(InheritedGetProvider.class);
                                            }
                                        }));

        assertContains(exception.getMessage(), "implements get(), which is never called for a DynamicProvider");
    }

    @Test
    public void testBoundByType()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("lime");
                                bind(Bar.class).toProvider(BarProvider.class);
                            }
                        });

        assertEquals("lime", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testBoundByTypeIsInjectedIntoMembers()
    {
        // Member injection links its dependencies through method handles rather than provisioning them
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("mango");
                                bind(Bar.class).toProvider(BarProvider.class);
                            }
                        });

        assertEquals("mango", injector.getInstance(FieldBarHolder.class).bar.name);
    }

    @Test
    public void testBoundByKeyUsesThatKeysBinding()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(NamingBarProvider.class).annotatedWith(named("kiwi")).toInstance(new NamingBarProvider("kiwi"));
                                bind(Bar.class).toProvider(Key.get(NamingBarProvider.class, named("kiwi")));
                            }
                        });

        assertEquals("kiwi", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testBoundByTypeIsConstructorInjected()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("quince");
                                bind(Bar.class).toProvider(ConstructorInjectedBarProvider.class);
                            }
                        });

        assertEquals("quince", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testBoundByTypeGetsTheProviderOnEveryProvision()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(ProviderHolder.class).toProvider(SelfProvidingProvider.class);
                            }
                        });

        assertNotSame(
                injector.getInstance(ProviderHolder.class).provider,
                injector.getInstance(ProviderHolder.class).provider);
    }

    @Test
    public void testBoundByTypeUsesTheProvidersScope()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(SelfProvidingProvider.class).in(Singleton.class);
                                bind(ProviderHolder.class).toProvider(SelfProvidingProvider.class);
                            }
                        });

        ProviderHolder first = injector.getInstance(ProviderHolder.class);
        ProviderHolder second = injector.getInstance(ProviderHolder.class);
        assertNotSame(first, second);
        assertSame(first.provider, second.provider);
    }

    @Test
    public void testBoundByTypeUsesTheBindingsScope()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(ProviderHolder.class).toProvider(SelfProvidingProvider.class).in(Singleton.class);
                            }
                        });

        assertSame(injector.getInstance(ProviderHolder.class), injector.getInstance(ProviderHolder.class));
    }

    @Test
    public void testBoundByTypeLiteralResolvesTypeArguments()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).toInstance("fig");
                                bind(new TypeLiteral<List<String>>() {})
                                        .toProvider(new TypeLiteral<ElementListProvider<String>>() {});
                            }
                        });

        assertEquals(List.of("fig"), injector.getInstance(new Key<List<String>>() {}));
    }

    @Test
    public void testBoundByTypeExposesTheProviderAsADependency()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("pomelo");
                                bind(Bar.class).toProvider(BarProvider.class);
                            }
                        });

        ProvidesMethodBinding<?> binding =
                injector.getBinding(Bar.class).acceptTargetVisitor(new ProvidesMethodVisitor());
        assertNull(binding.getEnclosingInstance());
        assertEquals(
                List.of(Key.get(BarProvider.class), Key.get(String.class, named("name"))),
                ((HasDependencies) binding).getDependencies().stream().map(Dependency::getKey).toList());
    }

    @Test
    public void testBoundByTypeInAMultibinder()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("yuzu");
                                Multibinder.newSetBinder(binder(), Bar.class)
                                        .addBinding()
                                        .toProvider(BarProvider.class);
                            }
                        });

        Set<Bar> bars = injector.getInstance(new Key<Set<Bar>>() {});
        assertEquals(1, bars.size());
        assertEquals("yuzu", bars.iterator().next().name);
    }

    @Test
    public void testBoundByTypeSurvivesElementRewriting()
    {
        Module module =
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(String.class).annotatedWith(named("name")).toInstance("citron");
                        bind(Bar.class).toProvider(BarProvider.class);
                    }
                };

        Injector injector = Guice.createInjector(Modules.override(module).with(new AbstractModule() {}));

        assertEquals("citron", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testBindingsByDifferentProviderKeysConflict()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(NamingBarProvider.class).annotatedWith(named("a")).toInstance(new NamingBarProvider("a"));
                                                bind(NamingBarProvider.class).annotatedWith(named("b")).toInstance(new NamingBarProvider("b"));
                                                bind(Bar.class).toProvider(Key.get(NamingBarProvider.class, named("a")));
                                                bind(Bar.class).toProvider(Key.get(NamingBarProvider.class, named("b")));
                                            }
                                        }));

        assertContains(exception.getMessage(), "was bound multiple times");
    }

    @Test
    public void testProvidedBy()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("loquat");
                            }
                        });

        assertEquals("loquat", injector.getInstance(ProvidedThing.class).name);
        assertNotSame(injector.getInstance(ProvidedThing.class), injector.getInstance(ProvidedThing.class));
    }

    @Test
    public void testProvidedByUsesTheTypesScope()
    {
        Injector injector = Guice.createInjector();

        assertSame(injector.getInstance(SingletonProvidedThing.class), injector.getInstance(SingletonProvidedThing.class));
    }

    @Test
    public void testProvidedByMissingDependencyFailsAtInjectorCreation()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                getProvider(ProvidedThing.class);
                                            }
                                        }));

        assertContains(
                exception.getMessage(), "No implementation for String annotated with @Named(\"name\")");
    }

    @Test
    public void testProvidedByRejectsAnInvalidProvider()
    {
        ConfigurationException exception =
                assertThrows(
                        ConfigurationException.class,
                        () -> Guice.createInjector().getInstance(BadlyProvidedThing.class));

        assertContains(exception.getMessage(), "must have exactly one @Provides method, found 2");
    }

    @Test
    public void testUnresolvedTypeParameterIsReportedWithoutStoppingTheModule()
    {
        Module module =
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(Bar.class).toProvider(new UnresolvedTypeProvider<Bar, String>());
                        bind(String.class).toInstance("bound after");
                    }
                };

        assertTrue(
                Elements.getElements(module).stream()
                        .anyMatch(element -> element instanceof Binding<?> binding && binding.getKey().equals(Key.get(String.class))));
        CreationException exception = assertThrows(CreationException.class, () -> Guice.createInjector(module));
        assertEquals(1, exception.getErrorMessages().size(), exception.getMessage());
        assertContains(exception.getMessage(), "U cannot be used as a key");
    }

    @Test
    public void testGenericInstanceIsResolvedAgainstTheBoundType()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).toInstance("pear");
                                bind(Integer.class).toInstance(7);
                                bind(new TypeLiteral<Labeled<String>>() {}).toProvider(new LabeledProvider<>("fruit"));
                                bind(new TypeLiteral<Labeled<Integer>>() {}).toProvider(new LabeledProvider<>("count"));
                            }
                        });

        Labeled<String> fruit = injector.getInstance(new Key<Labeled<String>>() {});
        assertEquals("fruit", fruit.label);
        assertEquals("pear", fruit.value);
        Labeled<Integer> count = injector.getInstance(new Key<Labeled<Integer>>() {});
        assertEquals("count", count.label);
        assertEquals(7, count.value);
    }

    @Test
    public void testGenericInstanceMembersAreResolvedAgainstTheBoundType()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).toInstance("plum");
                                bind(new TypeLiteral<List<String>>() {}).toProvider(new MemberTypedProvider<>());
                            }
                        });

        assertEquals(List.of("plum"), injector.getInstance(new Key<List<String>>() {}));
    }

    @Test
    public void testGenericInstanceBoundToASupertypeIsReported()
    {
        // The bound type does not determine E, so the provider method's parameter cannot be a key
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(new TypeLiteral<Collection<String>>() {}).toProvider(new ElementListProvider<String>());
                                            }
                                        }));

        assertEquals(1, exception.getErrorMessages().size(), exception.getMessage());
        assertContains(exception.getMessage(), "E cannot be used as a key");
    }

    @Test
    public void testNoProviderMethod()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Bar.class).toProvider(new NoMethodProvider());
                                            }
                                        }));

        assertContains(exception.getMessage(), "must have exactly one @Provides method, found 0");
    }

    @Test
    public void testTwoProviderMethods()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Bar.class).toProvider(new TwoMethodProvider());
                                            }
                                        }));

        assertContains(exception.getMessage(), "must have exactly one @Provides method, found 2");
    }

    @Test
    public void testWrongReturnType()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Foo.class).toProvider(new WrongReturnTypeProvider());
                                            }
                                        }));

        assertContains(exception.getMessage(), "which is not assignable to");
    }

    @Test
    public void testVoidProviderMethodIsRejected()
    {
        assertContains(creationErrorFor(new VoidProvider()), "returns void, which is not assignable to");
    }

    @Test
    public void testBindingAnnotationOnProviderMethodIsRejected()
    {
        assertContains(
                creationErrorFor(new BindingAnnotatedProvider()),
                "has a binding annotation, which a DynamicProvider would ignore");
    }

    @Test
    public void testScopeAnnotationOnProviderMethodIsRejected()
    {
        assertContains(
                creationErrorFor(new ScopeAnnotatedProvider()),
                "has a scope annotation, which a DynamicProvider would ignore");
    }

    @Test
    public void testRejectedInstanceReportsOnlyItsOwnError()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Foo.class).toProvider(new NoFooMethodProvider());
                                            }
                                        }));

        assertEquals(1, exception.getErrorMessages().size(), exception.getMessage());
        assertContains(exception.getMessage(), "must have exactly one @Provides method, found 0");
    }

    @Test
    public void testRejectedTypeReportsOnlyItsOwnError()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Foo.class).toProvider(NoFooMethodProvider.class);
                                            }
                                        }));

        assertEquals(1, exception.getErrorMessages().size(), exception.getMessage());
        assertContains(exception.getMessage(), "must have exactly one @Provides method, found 0");
    }

    @Test
    public void testInjectsFieldsAndVoidMethods()
    {
        MemberInjectedProvider provider = new MemberInjectedProvider();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("bergamot");
                                bind(Bar.class).toProvider(provider);
                            }
                        });

        assertEquals("bergamot", injector.getInstance(Bar.class).name);
        assertEquals("bergamot", provider.field);
        assertNotNull(provider.fromSetter);
    }

    @Test
    public void testMembersAreInjectedOnce()
    {
        CountingMemberProvider provider = new CountingMemberProvider();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Bar.class).toProvider(provider);
                            }
                        });

        injector.getInstance(Bar.class);
        injector.getInstance(Bar.class);
        injector.getInstance(Bar.class);

        assertEquals(1, provider.setterCalls);
        assertEquals(3, provider.providerMethodCalls);
    }

    @Test
    public void testMembersAreInjectedAtInjectorCreation()
    {
        CountingMemberProvider provider = new CountingMemberProvider();
        Guice.createInjector(
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(Bar.class).toProvider(provider);
                    }
                });

        assertEquals(1, provider.setterCalls);
        assertEquals(0, provider.providerMethodCalls);
    }

    @Test
    public void testMissingMemberBindingFailsAtInjectorCreation()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Bar.class).toProvider(new MissingMemberProvider());
                                            }
                                        }));

        assertContains(exception.getMessage(), "No implementation for Runnable");
    }

    @Test
    public void testMembersAreInjectedBeforeAnEarlierInstanceBindingUsesTheProvider()
    {
        assertEquals("members injected", nameSeenByInstanceBinding(true));
    }

    @Test
    public void testMembersAreInjectedBeforeALaterInstanceBindingUsesTheProvider()
    {
        assertEquals("members injected", nameSeenByInstanceBinding(false));
    }

    private static String nameSeenByInstanceBinding(boolean instanceFirst)
    {
        BarSetter barSetter = new BarSetter();
        Guice.createInjector(
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        if (instanceFirst) {
                            bind(BarSetter.class).toInstance(barSetter);
                        }
                        bind(Bar.class).toProvider(new MemberUsingProvider());
                        if (!instanceFirst) {
                            bind(BarSetter.class).toInstance(barSetter);
                        }
                    }
                });
        return barSetter.bar.name;
    }

    @Test
    public void testInjectionPointsAreExposedToTheSpi()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Bar.class).toProvider(new CountingMemberProvider());
                            }
                        });

        ProviderInstanceBinding<?> binding =
                assertInstanceOf(ProviderInstanceBinding.class, injector.getBinding(Bar.class));
        assertEquals(
                Set.of("setDep"),
                binding.getInjectionPoints().stream()
                        .map(injectionPoint -> injectionPoint.getMember().getName())
                        .collect(toSet()));
    }

    @Test
    public void testOptionalMemberInjection()
    {
        OptionalMemberProvider provider = new OptionalMemberProvider();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Bar.class).toProvider(provider);
                            }
                        });

        injector.getInstance(Bar.class);
        assertNull(provider.missing);
    }

    @Test
    public void testSubtypeReturnTypeIsAllowed()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("kumquat");
                                bind(Bar.class).toProvider(new SubtypeProvider());
                            }
                        });

        assertTrue(injector.getInstance(Bar.class) instanceof SubBar);
    }

    @Test
    public void testWildcardReturnTypeIsAnUncheckedConversion()
    {
        // The key's type argument is only known at runtime, so the caller casts the key, as JsonCodecBinder does
        @SuppressWarnings("unchecked")
        Key<Codec<?>> key = (Key<Codec<?>>) Key.get(Types.newParameterizedTypeWithOwner(DynamicProviderTest.class, Codec.class, String.class));
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(key).toProvider(new WildcardCodecProvider(String.class));
                            }
                        });

        assertEquals(String.class, injector.getInstance(new Key<Codec<String>>() {}).type);
    }

    @Test
    public void testWildcardSubtypeReturnTypeIsAnUncheckedConversion()
    {
        @SuppressWarnings("unchecked")
        Key<List<?>> key = (Key<List<?>>) Key.get(new TypeLiteral<List<String>>() {}.getType());
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(key).toProvider(new WildcardArrayListProvider());
                            }
                        });

        assertEquals(List.of("guava"), injector.getInstance(new Key<List<String>>() {}));
    }

    @Test
    public void testWildcardReturnTypeThatCannotMatchIsRejected()
    {
        @SuppressWarnings("unchecked")
        Key<List<? extends Number>> key =
                (Key<List<? extends Number>>) Key.get(new TypeLiteral<List<String>>() {}.getType());
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(key).toProvider(new NumberListProvider());
                                            }
                                        }));

        assertContains(
                exception.getMessage(), "returns List<? extends Number>, which is not assignable to List<String>");
    }

    @Test
    public void testReturnTypeArgumentsMustMatch()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(new TypeLiteral<List<Integer>>() {})
                                                        .toProvider(new StringListProvider());
                                            }
                                        }));

        assertContains(exception.getMessage(), "returns List<String>, which is not assignable to List<Integer>");
    }

    @Test
    public void testReturnTypeIsResolvedAgainstTheProviderClass()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).toInstance("kiwi");
                                bind(new TypeLiteral<List<String>>() {}).toProvider(new StringArrayListProvider());
                            }
                        });

        assertEquals(List.of("kiwi"), injector.getInstance(new Key<List<String>>() {}));
    }

    @Test
    public void testInheritedProviderMethod()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(String.class).annotatedWith(named("name")).toInstance("pomelo");
                                bind(Bar.class).toProvider(new InheritingBarProvider());
                            }
                        });

        assertEquals("pomelo", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testAnnotatedOverrideOfProviderMethodIsRejected()
    {
        assertContains(
                creationErrorFor(new AnnotatedOverrideProvider()),
                "Overriding @Provides methods is not allowed");
    }

    @Test
    public void testUnannotatedOverrideOfProviderMethodIsRejected()
    {
        assertContains(
                creationErrorFor(new UnannotatedOverrideProvider()),
                "Overriding @Provides methods is not allowed");
    }

    @Test
    public void testAbstractProviderMethodIsRejected()
    {
        assertContains(
                creationErrorFor(new ImplementingBarProvider()),
                "@Provides methods may not be abstract: DynamicProviderTest$AbstractMethodBarProvider.create()");
    }

    @Test
    public void testSurvivesElementRewriting()
    {
        Module module =
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(String.class).annotatedWith(named("name")).toInstance("citron");
                        bind(Bar.class).toProvider(new BarProvider());
                    }
                };

        Injector injector =
                Guice.createInjector(
                        Modules.override(module)
                                .with(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Foo.class).toProvider(new FooProvider());
                                            }
                                        }));

        assertEquals("citron", injector.getInstance(Bar.class).name);
        assertEquals("citron", injector.getInstance(Foo.class).name);
    }

    @Test
    public void testMembersAreInjectedWhenTheBindingIsApplied()
    {
        Module module =
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(String.class).toInstance("replaced");
                        bind(Bar.class).toProvider(new MemberUsingProvider());
                    }
                };

        Injector injector =
                Guice.createInjector(
                        Modules.override(module)
                                .with(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(String.class).toInstance("replacement");
                                            }
                                        }));

        assertEquals("members injected", injector.getInstance(Bar.class).name);
    }

    @Test
    public void testOverriddenBindingDoesNotInjectTheProvider()
    {
        MissingMemberProvider provider = new MissingMemberProvider();
        Module module =
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(Bar.class).toProvider(provider);
                    }
                };

        Injector injector =
                Guice.createInjector(
                        Modules.override(module)
                                .with(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Bar.class).toInstance(new Bar("overriding"));
                                            }
                                        }));

        assertEquals("overriding", injector.getInstance(Bar.class).name);
        assertNull(provider.missing);
    }

    @Test
    public void testSameProviderInTwoInjectors()
    {
        BarProvider provider = new BarProvider();
        Module module =
                new AbstractModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(String.class).annotatedWith(named("name")).toInstance("lime");
                        bind(Bar.class).toProvider(provider);
                    }
                };

        assertEquals("lime", Guice.createInjector(module).getInstance(Bar.class).name);
        assertEquals("lime", Guice.createInjector(module).getInstance(Bar.class).name);
    }

    static class Bar
    {
        final String name;

        Bar()
        {
            this("default");
        }

        Bar(String name)
        {
            this.name = name;
        }
    }

    static class SubBar
            extends Bar
    {
        SubBar(String name)
        {
            super(name);
        }
    }

    static class Foo
    {
        final String name;
        final Bar bar;

        Foo(String name, Bar bar)
        {
            this.name = name;
            this.bar = bar;
        }
    }

    static class BarHolder
    {
        final Bar bar;

        @Inject
        BarHolder(Bar bar)
        {
            this.bar = bar;
        }
    }

    static class FieldBarHolder
    {
        @Inject
        Bar bar;
    }

    static class NullableBarHolder
    {
        final Bar bar;

        @Inject
        NullableBarHolder(@Nullable Bar bar)
        {
            this.bar = bar;
        }
    }

    static class FooProvider
            implements DynamicProvider<Foo>
    {
        @Provides
        Foo create(@Named("name") String name, Bar bar)
        {
            return new Foo(name, bar);
        }
    }

    static class BarProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        Bar create(@Named("name") String name)
        {
            return new Bar(name);
        }
    }

    static class PrivateBarProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        private Bar create(@Named("name") String name)
        {
            return new Bar(name);
        }
    }

    static class ProviderArgumentProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        Bar create(Provider<String> name)
        {
            return new Bar("from " + name.get());
        }
    }

    static class SubtypeProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        SubBar create(@Named("name") String name)
        {
            return new SubBar(name);
        }
    }

    static class Codec<T>
    {
        final Type type;

        Codec(Type type)
        {
            this.type = type;
        }
    }

    static class WildcardCodecProvider
            implements DynamicProvider<Codec<?>>
    {
        private final Type type;

        WildcardCodecProvider(Type type)
        {
            this.type = type;
        }

        @Provides
        Codec<?> create()
        {
            return new Codec<>(type);
        }
    }

    static class WildcardArrayListProvider
            implements DynamicProvider<ArrayList<?>>
    {
        @Provides
        ArrayList<?> create()
        {
            return new ArrayList<>(List.of("guava"));
        }
    }

    static class NumberListProvider
            implements DynamicProvider<List<? extends Number>>
    {
        @Provides
        List<? extends Number> create()
        {
            return List.of(1);
        }
    }

    static class StringListProvider
            implements DynamicProvider<List<Integer>>
    {
        @Provides
        List<String> create()
        {
            return List.of("not an integer");
        }
    }

    abstract static class ArrayListProvider<E>
            implements DynamicProvider<List<E>>
    {
        @Provides
        ArrayList<E> create(E element)
        {
            return new ArrayList<>(List.of(element));
        }
    }

    static class StringArrayListProvider
            extends ArrayListProvider<String> {}

    abstract static class AbstractBarProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        Bar create(@Named("name") String name)
        {
            return new Bar(name);
        }
    }

    static class InheritingBarProvider
            extends AbstractBarProvider {}

    static class OverriddenBarProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        Bar create()
        {
            return new Bar("overridden");
        }
    }

    static class AnnotatedOverrideProvider
            extends OverriddenBarProvider
    {
        @Provides
        @Override
        Bar create()
        {
            return new Bar("override");
        }
    }

    static class UnannotatedOverrideProvider
            extends OverriddenBarProvider
    {
        @Override
        Bar create()
        {
            return new Bar("override");
        }
    }

    abstract static class AbstractMethodBarProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        abstract Bar create(@Named("name") String name);
    }

    static class ImplementingBarProvider
            extends AbstractMethodBarProvider
    {
        @Override
        Bar create(String name)
        {
            return new Bar(name);
        }
    }

    static class CountingProvider
            implements DynamicProvider<Integer>
    {
        private int count;

        @Provides
        Integer next()
        {
            return ++count;
        }
    }

    static class ThrowingProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        Bar create()
        {
            throw new UnsupportedOperationException("boom");
        }
    }

    static class NullProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        Bar create()
        {
            return null;
        }
    }

    static class NoMethodProvider
            implements DynamicProvider<Bar> {}

    private static String creationErrorFor(DynamicProvider<Bar> provider)
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () ->
                                Guice.createInjector(
                                        new AbstractModule()
                                        {
                                            @Override
                                            protected void configure()
                                            {
                                                bind(Bar.class).toProvider(provider);
                                            }
                                        }));
        return exception.getMessage();
    }

    static class Dep {}

    static class GetImplementedProvider
            implements DynamicProvider<Bar>
    {
        @Override
        public Bar get()
        {
            return new Bar("from get");
        }

        @Provides
        Bar create()
        {
            return new Bar();
        }
    }

    static class ThrowingGetProvider
            implements DynamicProvider<Bar>
    {
        @Override
        public Bar get()
        {
            throw new IllegalStateException("nope");
        }

        @Provides
        Bar create()
        {
            return new Bar();
        }
    }

    interface GetProvidingProvider
            extends DynamicProvider<Bar>
    {
        @Override
        default Bar get()
        {
            return new Bar("from default method");
        }
    }

    static class InheritedGetProvider
            implements GetProvidingProvider
    {
        @Provides
        Bar create()
        {
            return new Bar();
        }
    }

    static class UnsupportedGetProvider
            implements DynamicProvider<Bar>
    {
        @Override
        public Bar get()
        {
            throw new UnsupportedOperationException();
        }

        @Provides
        Bar create()
        {
            return new Bar();
        }
    }

    static class MemberInjectedProvider
            implements DynamicProvider<Bar>
    {
        @Inject
        @Named("name")
        String field;

        Dep fromSetter;

        @Inject
        void setFromSetter(Dep dep)
        {
            this.fromSetter = dep;
        }

        @Provides
        Bar create(@Named("name") String name)
        {
            return new Bar(name);
        }
    }

    static class CountingMemberProvider
            implements DynamicProvider<Bar>
    {
        int setterCalls;
        int providerMethodCalls;

        @Inject
        void setDep(Dep dep)
        {
            setterCalls++;
        }

        @Provides
        Bar create()
        {
            providerMethodCalls++;
            return new Bar();
        }
    }

    static class MissingMemberProvider
            implements DynamicProvider<Bar>
    {
        @Inject
        Runnable missing;

        @Provides
        Bar create()
        {
            return new Bar();
        }
    }

    static class MemberUsingProvider
            implements DynamicProvider<Bar>
    {
        @Inject
        Dep dep;

        @Provides
        Bar create()
        {
            return new Bar(dep == null ? "members not injected" : "members injected");
        }
    }

    static class BarSetter
    {
        Bar bar;

        @Inject
        void setBar(Bar bar)
        {
            this.bar = bar;
        }
    }

    static class NoFooMethodProvider
            implements DynamicProvider<Foo> {}

    static class NamingBarProvider
            implements DynamicProvider<Bar>
    {
        private final String name;

        NamingBarProvider(String name)
        {
            this.name = name;
        }

        @Provides
        Bar create()
        {
            return new Bar(name);
        }
    }

    static class ConstructorInjectedBarProvider
            implements DynamicProvider<Bar>
    {
        private final String name;

        @Inject
        ConstructorInjectedBarProvider(@Named("name") String name)
        {
            this.name = name;
        }

        @Provides
        Bar create()
        {
            return new Bar(name);
        }
    }

    static class ProviderHolder
    {
        final Object provider;

        ProviderHolder(Object provider)
        {
            this.provider = provider;
        }
    }

    static class SelfProvidingProvider
            implements DynamicProvider<ProviderHolder>
    {
        @Provides
        ProviderHolder create()
        {
            return new ProviderHolder(this);
        }
    }

    static class ElementListProvider<E>
            implements DynamicProvider<List<E>>
    {
        @Provides
        List<E> create(E element)
        {
            return List.of(element);
        }
    }

    static class UnresolvedTypeProvider<T, U>
            implements DynamicProvider<T>
    {
        @Provides
        T create(U unresolved)
        {
            return null;
        }
    }

    static class LabeledProvider<T>
            implements DynamicProvider<Labeled<T>>
    {
        private final String label;

        LabeledProvider(String label)
        {
            this.label = label;
        }

        @Provides
        Labeled<T> create(T value)
        {
            return new Labeled<>(label, value);
        }
    }

    static class Labeled<T>
    {
        final String label;
        final T value;

        Labeled(String label, T value)
        {
            this.label = label;
            this.value = value;
        }
    }

    static class MemberTypedProvider<T>
            implements DynamicProvider<List<T>>
    {
        @Inject
        T member;

        @Provides
        List<T> create()
        {
            return List.of(member);
        }
    }

    @ProvidedBy(ProvidedThingProvider.class)
    static class ProvidedThing
    {
        final String name;

        ProvidedThing(String name)
        {
            this.name = name;
        }
    }

    static class ProvidedThingProvider
            implements DynamicProvider<ProvidedThing>
    {
        @Provides
        ProvidedThing create(@Named("name") String name)
        {
            return new ProvidedThing(name);
        }
    }

    @Singleton
    @ProvidedBy(SingletonProvidedThingProvider.class)
    static class SingletonProvidedThing {}

    static class SingletonProvidedThingProvider
            implements DynamicProvider<SingletonProvidedThing>
    {
        @Provides
        SingletonProvidedThing create()
        {
            return new SingletonProvidedThing();
        }
    }

    @ProvidedBy(BadlyProvidedThingProvider.class)
    static class BadlyProvidedThing {}

    static class BadlyProvidedThingProvider
            implements DynamicProvider<BadlyProvidedThing>
    {
        @Provides
        BadlyProvidedThing create()
        {
            return new BadlyProvidedThing();
        }

        @Provides
        BadlyProvidedThing createAnother()
        {
            return new BadlyProvidedThing();
        }
    }

    static class OptionalMemberProvider
            implements DynamicProvider<Bar>
    {
        @Inject(optional = true)
        Runnable missing;

        @Provides
        Bar create()
        {
            return new Bar();
        }
    }

    static class TwoMethodProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        Bar one()
        {
            return new Bar("one");
        }

        @Provides
        Bar two()
        {
            return new Bar("two");
        }
    }

    static class WrongReturnTypeProvider
            implements DynamicProvider<Foo>
    {
        @Provides
        Bar create()
        {
            return new Bar("wrong");
        }
    }

    static class VoidProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        void create() {}
    }

    static class BindingAnnotatedProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        @Named("ignored")
        Bar create()
        {
            return new Bar();
        }
    }

    static class ScopeAnnotatedProvider
            implements DynamicProvider<Bar>
    {
        @Provides
        @Singleton
        Bar create()
        {
            return new Bar();
        }
    }

    static class ProvidesMethodVisitor
            extends DefaultBindingTargetVisitor<Object, ProvidesMethodBinding<?>>
            implements ProvidesMethodTargetVisitor<Object, ProvidesMethodBinding<?>>
    {
        @Override
        public ProvidesMethodBinding<?> visit(ProvidesMethodBinding<?> binding)
        {
            return binding;
        }
    }

    @Retention(RUNTIME)
    @Target(PARAMETER)
    @interface Nullable {}
}
