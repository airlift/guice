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

import com.google.inject.internal.Annotations;
import com.google.inject.spi.Element;
import com.google.inject.spi.ElementSource;
import com.google.inject.spi.Elements;
import com.google.inject.util.Modules;
import com.google.inject.util.Providers;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.inject.Asserts.assertContains;
import static com.google.inject.name.Names.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RebindTest
{
    @Test
    public void testRebindReplacesBinding()
    {
        Injector injector = Guice.createInjector(bindFoo(FooA.class), rebindFoo(FooB.class));

        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));
    }

    @Test
    public void testRebindReplacesEachKindOfBindingWithEachKind()
    {
        List<Module> originals =
                List.of(binder -> binder.bind(Foo.class).to(FooA.class),
                        binder -> binder.bind(Foo.class).toInstance(new FooA()),
                        binder -> binder.bind(Foo.class).toProvider(Providers.of(new FooA())),
                        binder -> binder.bind(Foo.class).toProvider(FooAProvider.class),
                        binder -> binder.bind(Foo.class).toConstructor(fooAConstructor()),
                        binder -> binder.install(
                                new AbstractModule()
                                {
                                    @Provides
                                    Foo provideFoo()
                                    {
                                        return new FooA();
                                    }
                                }));
        List<Module> rebinds =
                List.of(binder -> binder.rebind(Foo.class).to(FooB.class),
                        binder -> binder.rebind(Foo.class).toInstance(new FooB()),
                        binder -> binder.rebind(Foo.class).toProvider(Providers.of(new FooB())),
                        binder -> binder.rebind(Foo.class).toProvider(FooBProvider.class),
                        binder -> binder.rebind(Foo.class).toConstructor(fooBConstructor()));

        for (Module original : originals) {
            for (Module rebind : rebinds) {
                Injector injector = Guice.createInjector(original, rebind);
                assertInstanceOf(FooB.class, injector.getInstance(Foo.class));

                // rebinds are kept when elements are rewritten into a module
                Module rewritten = Elements.getModule(Elements.getElements(original, rebind));
                assertInstanceOf(FooB.class, Guice.createInjector(rewritten).getInstance(Foo.class));
            }
        }
    }

    @Test
    public void testRebindInModule()
    {
        TypeLiteral<List<String>> strings = new TypeLiteral<List<String>>() {};
        Key<Foo> namedFoo = Key.get(Foo.class, named("foo"));

        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Foo.class).to(FooA.class);
                                bind(strings).toInstance(List.of("a"));
                                bind(namedFoo).to(FooA.class);
                            }
                        },
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                rebind(Foo.class).to(FooB.class);
                                rebind(strings).toInstance(List.of("b"));
                                rebind(namedFoo).to(FooB.class);
                            }
                        });

        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));
        assertEquals(List.of("b"), injector.getInstance(Key.get(strings)));
        assertInstanceOf(FooB.class, injector.getInstance(namedFoo));
    }

    @Test
    public void testRebindUntargetedBinding()
    {
        Injector injector =
                Guice.createInjector(
                        binder -> binder.bind(FooA.class),
                        binder -> binder.rebind(FooA.class).to(SubFooA.class));
        assertInstanceOf(SubFooA.class, injector.getInstance(FooA.class));

        injector =
                Guice.createInjector(
                        binder -> binder.bind(FooA.class).to(SubFooA.class),
                        binder -> binder.rebind(FooA.class));
        assertSame(FooA.class, injector.getInstance(FooA.class).getClass());
    }

    @Test
    @SuppressWarnings("rawtypes") // a constant bound to a class is keyed by the raw Class type
    public void testRebindConstant()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bindConstant().annotatedWith(named("port")).to(8080);
                                bindConstant().annotatedWith(named("host")).to("localhost");
                                bindConstant().annotatedWith(named("type")).to(FooA.class);
                                bindConstant().annotatedWith(named("timeout")).to("10");
                            }
                        },
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                rebindConstant().annotatedWith(named("port")).to(9090);
                                rebindConstant().annotatedWith(named("host")).to("example.com");
                                rebindConstant().annotatedWith(named("type")).to(FooB.class);
                                rebindConstant().annotatedWith(named("timeout")).to("20");
                            }
                        });

        assertEquals(9090, injector.getInstance(Key.get(Integer.class, named("port"))));
        assertEquals("example.com", injector.getInstance(Key.get(String.class, named("host"))));
        assertEquals(FooB.class, injector.getInstance(Key.get(Class.class, named("type"))));
        // a rebound string constant is still converted to the injected type
        assertEquals(20, injector.getInstance(Key.get(Integer.class, named("timeout"))));

        // a constant can also be rebound through its key
        injector =
                Guice.createInjector(
                        binder -> binder.bindConstant().annotatedWith(named("port")).to(8080),
                        binder -> binder.rebind(Key.get(Integer.class, named("port"))).toInstance(9090));
        assertEquals(9090, injector.getInstance(Key.get(Integer.class, named("port"))));
    }

    @Test
    public void testRebindConstantOfDifferentType()
    {
        // the rebind's key is Integer, but the constant it was meant for is a String
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                binder -> binder.bindConstant().annotatedWith(named("port")).to("8080"),
                                binder -> binder.rebindConstant().annotatedWith(named("port")).to(9090)));

        assertContains(
                exception.getMessage(),
                "Could not rebind() Integer annotated with @Named(%s), there is no earlier binding"
                        .formatted(Annotations.memberValueString("value", "port")));
    }

    @Test
    public void testRebindDoesNotKeepScope()
    {
        Injector injector =
                Guice.createInjector(
                        binder -> binder.bind(Foo.class).to(FooA.class).in(Singleton.class),
                        rebindFoo(FooB.class));

        assertNotSame(injector.getInstance(Foo.class), injector.getInstance(Foo.class));
    }

    @Test
    public void testRebindWithScope()
    {
        Injector injector =
                Guice.createInjector(
                        bindFoo(FooA.class),
                        binder -> binder.rebind(Foo.class).to(FooB.class).in(Singleton.class));

        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));
        assertSame(injector.getInstance(Foo.class), injector.getInstance(Foo.class));
    }

    @Test
    public void testReplacedBindingIsNeverProcessed()
    {
        AtomicInteger provisions = new AtomicInteger();
        Injector injector =
                Guice.createInjector(
                        Stage.PRODUCTION,
                        binder -> {
                            // each of these would fail injector creation, or provision, if processed
                            binder.bind(Foo.class).annotatedWith(named("linked")).to(NeedsUnbound.class);
                            binder.bind(Foo.class).annotatedWith(named("instance")).toInstance(new InjectedFoo());
                            binder.bind(Foo.class)
                                    .annotatedWith(named("eager"))
                                    .toProvider(
                                            (Provider<Foo>) () -> {
                                                provisions.incrementAndGet();
                                                return new FooA();
                                            })
                                    .asEagerSingleton();
                        },
                        binder -> {
                            binder.rebind(Foo.class).annotatedWith(named("linked")).to(FooB.class);
                            binder.rebind(Foo.class).annotatedWith(named("instance")).to(FooB.class);
                            binder.rebind(Foo.class).annotatedWith(named("eager")).to(FooB.class);
                        });

        assertEquals(0, provisions.get());
        assertInstanceOf(FooB.class, injector.getInstance(Key.get(Foo.class, named("linked"))));
        assertInstanceOf(FooB.class, injector.getInstance(Key.get(Foo.class, named("instance"))));
        assertInstanceOf(FooB.class, injector.getInstance(Key.get(Foo.class, named("eager"))));
        // the replaced linked binding didn't create a just-in-time binding for its target
        assertNull(injector.getExistingBinding(Key.get(NeedsUnbound.class)));
    }

    @Test
    public void testRebindReplacesPreviousRebind()
    {
        Injector injector =
                Guice.createInjector(bindFoo(FooA.class), rebindFoo(FooB.class), rebindFoo(FooC.class));

        assertInstanceOf(FooC.class, injector.getInstance(Foo.class));
    }

    @Test
    public void testRebindReplacesAllPrecedingBindings()
    {
        // FooA and FooC on their own would be a duplicate binding error
        Injector injector =
                Guice.createInjector(bindFoo(FooA.class), bindFoo(FooC.class), rebindFoo(FooB.class));

        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));
    }

    @Test
    public void testBindingAfterRebindIsDuplicate()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                bindFoo(FooA.class), rebindFoo(FooB.class), bindFoo(FooC.class)));
        assertContains(exception.getMessage(), "RebindTest$Foo was bound multiple times.");

        // an identical binding after a rebind is an ordinary, tolerated duplicate
        Injector injector =
                Guice.createInjector(bindFoo(FooA.class), rebindFoo(FooB.class), bindFoo(FooB.class));
        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));
    }

    @Test
    public void testRebindWithSource()
    {
        Injector injector =
                Guice.createInjector(
                        binder -> {
                            binder.bind(Foo.class).annotatedWith(named("source")).to(FooA.class);
                            binder.bind(Foo.class).annotatedWith(named("skipSources")).to(FooA.class);
                        },
                        binder -> {
                            binder.withSource("source")
                                    .rebind(Foo.class)
                                    .annotatedWith(named("source"))
                                    .to(FooB.class);
                            binder.skipSources(RebindTest.class)
                                    .rebind(Foo.class)
                                    .annotatedWith(named("skipSources"))
                                    .to(FooB.class);
                        });

        assertInstanceOf(FooB.class, injector.getInstance(Key.get(Foo.class, named("source"))));
        ElementSource source =
                (ElementSource) injector.getBinding(Key.get(Foo.class, named("source"))).getSource();
        assertEquals("source", source.getDeclaringSource());
        assertInstanceOf(FooB.class, injector.getInstance(Key.get(Foo.class, named("skipSources"))));
    }

    @Test
    public void testRebindInPrivateModule()
    {
        Injector injector =
                Guice.createInjector(
                        new PrivateModule()
                        {
                            @Override
                            protected void configure()
                            {
                                bind(Foo.class).to(FooA.class);
                                rebind(Foo.class).to(FooB.class);
                                expose(Foo.class);

                                bindConstant().annotatedWith(named("port")).to(8080);
                                rebindConstant().annotatedWith(named("port")).to(9090);
                                expose(Integer.class).annotatedWith(named("port"));
                            }
                        });

        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));
        assertEquals(9090, injector.getInstance(Key.get(Integer.class, named("port"))));
    }

    @Test
    public void testRebindWithoutBinding()
    {
        CreationException exception =
                assertThrows(CreationException.class, () -> Guice.createInjector(rebindFoo(FooB.class)));

        assertEquals(1, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "[Guice/RebindWithoutBinding]: Could not rebind() RebindTest$Foo, there is no earlier"
                        + " binding of it to replace.",
                "at RebindTest");
    }

    @Test
    public void testRebindOfJustInTimeBinding()
    {
        // FooA is injectable without a binding, but a rebind needs a binding to replace
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(binder -> binder.rebind(FooA.class).to(SubFooA.class)));

        assertContains(
                exception.getMessage(),
                "Could not rebind() RebindTest$FooA, there is no earlier binding of it to replace.");
    }

    @Test
    public void testRebindOfDifferentlyAnnotatedKey()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                binder -> binder.bind(Foo.class).annotatedWith(named("a")).to(FooA.class),
                                binder -> binder.rebind(Foo.class).annotatedWith(named("b")).to(FooB.class)));

        assertContains(
                exception.getMessage(),
                "Could not rebind() RebindTest$Foo annotated with @Named(%s), there is no earlier binding"
                        .formatted(Annotations.memberValueString("value", "b")));
    }

    @Test
    public void testRebindBeforeBinding()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(rebindFoo(FooB.class), bindFoo(FooA.class)));

        // the misplaced rebind is dropped, so the binding after it isn't also reported as a duplicate
        assertEquals(1, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "Could not rebind() RebindTest$Foo, it is bound only after the rebind, at",
                "RebindTest",
                "A rebind must come after the binding it replaces.");
    }

    @Test
    public void testRebindOfParentBinding()
    {
        Injector parent = Guice.createInjector(bindFoo(FooA.class));

        CreationException exception =
                assertThrows(
                        CreationException.class, () -> parent.createChildInjector(rebindFoo(FooB.class)));

        assertEquals(1, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "Could not rebind() RebindTest$Foo, it is bound in a parent injector. A rebind can only"
                        + " replace a binding in the same injector.");
    }

    @Test
    public void testRebindOfGrandparentBinding()
    {
        Injector parent = Guice.createInjector(bindFoo(FooA.class)).createChildInjector();

        CreationException exception =
                assertThrows(
                        CreationException.class, () -> parent.createChildInjector(rebindFoo(FooB.class)));

        assertContains(
                exception.getMessage(), "Could not rebind() RebindTest$Foo, it is bound in a parent injector.");
    }

    @Test
    public void testRebindInPrivateModuleOfEnclosingBinding()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                bindFoo(FooA.class),
                                new PrivateModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        rebind(Foo.class).to(FooB.class);
                                    }
                                }));

        assertEquals(1, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(), "Could not rebind() RebindTest$Foo, it is bound in a parent injector.");
    }

    @Test
    public void testRebindOfExposedBinding()
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
                                        bind(Foo.class).to(FooA.class);
                                        expose(Foo.class);
                                    }
                                },
                                rebindFoo(FooB.class)));

        assertEquals(1, exception.getErrorMessages().size());
        assertContains(
                exception.getMessage(),
                "Could not rebind() RebindTest$Foo, it is exposed from a private module. Rebind it within"
                        + " that private module instead.");
    }

    @Test
    public void testElementsRecordRebinds()
    {
        List<Element> elements = Elements.getElements(bindFoo(FooA.class), rebindFoo(FooA.class));

        assertEquals(2, elements.size());
        Binding<?> original = (Binding<?>) elements.get(0);
        Binding<?> rebind = (Binding<?>) elements.get(1);
        assertFalse(original.isRebind());
        assertTrue(rebind.isRebind());
        assertNotEquals(original, rebind);

        // the bindings an injector ends up with are never rebinds
        Injector injector = Guice.createInjector(bindFoo(FooA.class), rebindFoo(FooB.class));
        assertFalse(injector.getBinding(Foo.class).isRebind());
    }

    @Test
    public void testOverrideReplacesRebind()
    {
        Module base = Modules.combine(bindFoo(FooA.class), rebindFoo(FooB.class));

        Injector injector = Guice.createInjector(Modules.override(base).with(bindFoo(FooC.class)));

        assertInstanceOf(FooC.class, injector.getInstance(Foo.class));
    }

    @Test
    public void testOverrideKeepsRebindOfOtherKey()
    {
        Module base = Modules.combine(bindFoo(FooA.class), rebindFoo(FooB.class));

        Injector injector =
                Guice.createInjector(
                        Modules.override(base)
                                .with(binder -> binder.bind(Foo.class).annotatedWith(named("other")).to(FooC.class)));

        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));
    }

    @Test
    public void testOverrideReplacesRebindInPrivateModule()
    {
        Module privateModule =
                new PrivateModule()
                {
                    @Override
                    protected void configure()
                    {
                        bind(Foo.class).to(FooA.class);
                        rebind(Foo.class).to(FooB.class);
                        expose(Foo.class);
                    }
                };

        // a private module on its own is overridden from within
        Injector injector =
                Guice.createInjector(Modules.override(privateModule).with(bindFoo(FooC.class)));
        assertInstanceOf(FooC.class, injector.getInstance(Foo.class));

        // along with other modules, its exposed bindings are overridden from outside
        Module base =
                Modules.combine(
                        privateModule,
                        binder -> binder.bind(Foo.class).annotatedWith(named("other")).to(FooA.class));
        injector = Guice.createInjector(Modules.override(base).with(bindFoo(FooC.class)));
        assertInstanceOf(FooC.class, injector.getInstance(Foo.class));
    }

    @Test
    public void testRebindInOverrideModule()
    {
        // an override that only rebinds a key replaces the overridden modules' bindings of it
        Injector injector =
                Guice.createInjector(Modules.override(bindFoo(FooA.class)).with(rebindFoo(FooB.class)));
        assertInstanceOf(FooB.class, injector.getInstance(Foo.class));

        Module base = Modules.combine(bindFoo(FooA.class), rebindFoo(FooB.class));
        injector = Guice.createInjector(Modules.override(base).with(rebindFoo(FooC.class)));
        assertInstanceOf(FooC.class, injector.getInstance(Foo.class));

        // an override that binds the key and then rebinds it overrides it as usual
        injector =
                Guice.createInjector(
                        Modules.override(bindFoo(FooA.class))
                                .with(bindFoo(FooB.class), rebindFoo(FooC.class)));
        assertInstanceOf(FooC.class, injector.getInstance(Foo.class));

        // the rebind still needs a binding to replace
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(
                                Modules.override(Modules.EMPTY_MODULE).with(rebindFoo(FooB.class))));
        assertContains(
                exception.getMessage(),
                "Could not rebind() RebindTest$Foo, there is no earlier binding of it to replace.");
    }

    @Test
    public void testDefaultRebindRecordsErrorAndBinds()
    {
        List<String> errors = new ArrayList<>();
        List<Object> bound = new ArrayList<>();
        Binder binder =
                (Binder) Proxy.newProxyInstance(
                        Binder.class.getClassLoader(),
                        new Class<?>[] {Binder.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "rebind", "rebindConstant" -> InvocationHandler.invokeDefault(proxy, method, args);
                            case "bind" -> {
                                bound.add(args[0]);
                                yield null;
                            }
                            case "bindConstant" -> {
                                bound.add("constant");
                                yield null;
                            }
                            case "addError" -> {
                                errors.add(((String) args[0]).formatted((Object[]) args[1]));
                                yield null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        });

        binder.rebind(Key.get(Foo.class));
        binder.rebind(TypeLiteral.get(Foo.class));
        binder.rebind(Foo.class);
        binder.rebindConstant();

        assertEquals(
                List.of(Key.get(Foo.class), TypeLiteral.get(Foo.class), Foo.class, "constant"), bound);
        assertEquals(4, errors.size());
        assertContains(errors.getFirst(), "rebind() is not supported by");
        assertContains(errors.getLast(), "rebindConstant() is not supported by");
    }

    private static Module bindFoo(Class<? extends Foo> implementation)
    {
        return binder -> binder.bind(Foo.class).to(implementation);
    }

    private static Module rebindFoo(Class<? extends Foo> implementation)
    {
        return binder -> binder.rebind(Foo.class).to(implementation);
    }

    private static Constructor<FooA> fooAConstructor()
    {
        try {
            return FooA.class.getConstructor();
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Constructor<FooB> fooBConstructor()
    {
        try {
            return FooB.class.getConstructor();
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    interface Foo {}

    static class FooA
            implements Foo
    {
        public FooA() {}
    }

    static class SubFooA
            extends FooA {}

    static class FooB
            implements Foo
    {
        public FooB() {}
    }

    static class FooC
            implements Foo {}

    static class FooAProvider
            implements Provider<Foo>
    {
        @Override
        public Foo get()
        {
            return new FooA();
        }
    }

    static class FooBProvider
            implements Provider<Foo>
    {
        @Override
        public Foo get()
        {
            return new FooB();
        }
    }

    interface Unbound {}

    static class NeedsUnbound
            implements Foo
    {
        @Inject
        NeedsUnbound(Unbound unbound) {}
    }

    static class InjectedFoo
            implements Foo
    {
        @Inject
        void inject(Unbound unbound) {}
    }
}
