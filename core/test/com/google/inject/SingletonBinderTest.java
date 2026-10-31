package com.google.inject;

import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.spi.DefaultBindingScopingVisitor;
import com.google.inject.spi.Element;
import com.google.inject.spi.ElementSource;
import com.google.inject.spi.Elements;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.google.inject.Asserts.assertContains;
import static com.google.inject.Asserts.getDeclaringSourcePart;
import static com.google.inject.SingletonModule.singletonModule;
import static com.google.inject.name.Names.named;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SingletonBinderTest
{
    private static final Object EAGER_SINGLETON = "eager singleton";

    @Test
    public void testUntargetted()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().asSingleton().bind(ServiceImpl.class);
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(ServiceImpl.class)));
        assertSame(injector.getInstance(ServiceImpl.class), injector.getInstance(ServiceImpl.class));
    }

    @Test
    public void testLinked()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().asSingleton().bind(Service.class).to(ServiceImpl.class);
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Service.class)));
        assertSame(injector.getInstance(Service.class), injector.getInstance(Service.class));
        // only the bound key is scoped, not the target's just-in-time binding
        assertNotSame(injector.getInstance(ServiceImpl.class), injector.getInstance(ServiceImpl.class));
    }

    @Test
    public void testAnnotatedWith()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                SingletonBinder singletons = binder().asSingleton();
                                singletons.bind(Service.class).annotatedWith(Red.class).to(ServiceImpl.class);
                                singletons.bind(Service.class).annotatedWith(named("blue")).to(ServiceImpl.class);
                            }
                        });

        for (Key<Service> key : List.of(Key.get(Service.class, Red.class), Key.get(Service.class, named("blue")))) {
            assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(key)));
            assertSame(injector.getInstance(key), injector.getInstance(key));
        }
        assertNotSame(
                injector.getInstance(Key.get(Service.class, Red.class)),
                injector.getInstance(Key.get(Service.class, named("blue"))));
    }

    @Test
    public void testKeyAndTypeLiteral()
    {
        Key<Service> key = Key.get(Service.class, named("key"));
        TypeLiteral<List<String>> listType = new TypeLiteral<>() {};
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().asSingleton().bind(key).to(ServiceImpl.class);
                                binder().asSingleton().bind(listType).toProvider(ArrayList::new);
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(key)));
        assertSame(injector.getInstance(key), injector.getInstance(key));
        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Key.get(listType))));
        assertSame(injector.getInstance(Key.get(listType)), injector.getInstance(Key.get(listType)));
    }

    @Test
    public void testProviderInstanceIsLazy()
    {
        CountingProvider provider = new CountingProvider();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().asSingleton().bind(Integer.class).toProvider(provider);
                            }
                        });

        assertEquals(0, provider.count);
        assertEquals(1, (int) injector.getInstance(Integer.class));
        assertEquals(1, (int) injector.getInstance(Integer.class));
        assertEquals(1, provider.count);
    }

    @Test
    public void testProviderTypes()
    {
        Key<Service> byClass = Key.get(Service.class, named("class"));
        Key<Service> byTypeLiteral = Key.get(Service.class, named("typeLiteral"));
        Key<Service> byKey = Key.get(Service.class, named("key"));
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                SingletonBinder singletons = binder().asSingleton();
                                singletons.bind(byClass).toProvider(ServiceProvider.class);
                                singletons.bind(byTypeLiteral).toProvider(TypeLiteral.get(ServiceProvider.class));
                                singletons.bind(byKey).toProvider(Key.get(ServiceProvider.class));
                            }
                        });

        for (Key<Service> key : List.of(byClass, byTypeLiteral, byKey)) {
            assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(key)));
            assertSame(injector.getInstance(key), injector.getInstance(key));
        }
    }

    @Test
    public void testConstructor()
            throws Exception
    {
        Constructor<ServiceImpl> constructor = ServiceImpl.class.getConstructor();
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().asSingleton().bind(Service.class).toConstructor(constructor);
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Service.class)));
        assertSame(injector.getInstance(Service.class), injector.getInstance(Service.class));
    }

    @Test
    public void testEagerSingleton()
    {
        CountingProvider provider = new CountingProvider();
        CountingProvider annotatedProvider = new CountingProvider();
        Injector injector =
                Guice.createInjector(
                        Stage.DEVELOPMENT,
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                SingletonBinder singletons = binder().asSingleton().eagerly();
                                singletons.bind(Integer.class).toProvider(provider);
                                singletons.bind(Integer.class).annotatedWith(named("annotated")).toProvider(annotatedProvider);
                                singletons.bind(ServiceImpl.class);
                            }
                        });

        assertEquals(1, provider.count);
        assertEquals(1, annotatedProvider.count);
        assertSame(EAGER_SINGLETON, scopeOf(injector.getBinding(Integer.class)));
        assertSame(EAGER_SINGLETON, scopeOf(injector.getBinding(Key.get(Integer.class, named("annotated")))));
        assertSame(EAGER_SINGLETON, scopeOf(injector.getBinding(ServiceImpl.class)));
        assertEquals(1, (int) injector.getInstance(Integer.class));
        assertEquals(1, provider.count);
        assertSame(injector.getInstance(ServiceImpl.class), injector.getInstance(ServiceImpl.class));
    }

    @Test
    public void testEagerlyLeavesSingletonBinderLazy()
    {
        Injector injector =
                Guice.createInjector(
                        Stage.DEVELOPMENT,
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                SingletonBinder singletons = binder().asSingleton();
                                SingletonBinder eagerSingletons = singletons.eagerly();
                                assertSame(eagerSingletons, eagerSingletons.eagerly());
                                eagerSingletons.bind(Service.class).to(ServiceImpl.class);
                                singletons.bind(ServiceImpl.class);
                            }
                        });

        assertSame(EAGER_SINGLETON, scopeOf(injector.getBinding(Service.class)));
        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(ServiceImpl.class)));
    }

    @Test
    public void testPrivateModule()
    {
        Injector injector =
                Guice.createInjector(
                        new PrivateModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().asSingleton().bind(Service.class).to(ServiceImpl.class);
                                expose(Service.class);
                            }
                        });

        assertSame(injector.getInstance(Service.class), injector.getInstance(Service.class));
    }

    @Test
    public void testBindingSourceIsModule()
    {
        List<Element> elements =
                Elements.getElements(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().asSingleton().bind(Service.class).to(ServiceImpl.class);
                                binder().asSingleton().eagerly().bind(ServiceImpl.class);
                            }
                        });

        assertEquals(2, elements.size());
        for (Element element : elements) {
            assertContains(element.getSource().toString(), getDeclaringSourcePart(getClass()));
        }
    }

    @Test
    public void testErrorSourceIsModule()
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
                                        binder().asSingleton().bind(Service.class);
                                    }
                                }));

        assertContains(
                exception.getMessage(),
                "No implementation for SingletonBinderTest$Service was bound.",
                getDeclaringSourcePart(getClass()));
    }

    @Test
    public void testExplicitSource()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                binder().withSource("explicit source").asSingleton().bind(ServiceImpl.class);
                            }
                        });

        ElementSource source = (ElementSource) injector.getBinding(ServiceImpl.class).getSource();
        assertEquals("explicit source", source.getDeclaringSource());
    }

    @Test
    public void testSingletonModule()
    {
        CountingProvider provider = new CountingProvider();
        Injector injector =
                Guice.createInjector(
                        new SingletonModule()
                        {
                            @Override
                            public void configure(SingletonBinder singletons)
                            {
                                singletons.bind(Service.class).to(ServiceImpl.class);
                                singletons.bind(Integer.class).toProvider(provider);
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Service.class)));
        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Integer.class)));
        assertSame(injector.getInstance(Service.class), injector.getInstance(Service.class));
        assertEquals(0, provider.count);
        assertEquals(1, (int) injector.getInstance(Integer.class));
        assertEquals(1, (int) injector.getInstance(Integer.class));
    }

    @Test
    public void testSingletonModuleProviderMethods()
    {
        Key<Set<Service>> setKey = Key.get(new TypeLiteral<>() {});
        Injector injector =
                Guice.createInjector(
                        new SingletonModule()
                        {
                            @Override
                            public void configure(SingletonBinder singletons) {}

                            @Provides
                            Service service()
                            {
                                return new ServiceImpl();
                            }

                            @ProvidesIntoSet
                            Service serviceElement()
                            {
                                return new ServiceImpl();
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Service.class)));
        assertSame(injector.getInstance(Service.class), injector.getInstance(Service.class));
        assertSame(
                injector.getInstance(setKey).iterator().next(),
                injector.getInstance(setKey).iterator().next());
    }

    @Test
    public void testModuleLambdas()
    {
        SingletonModule singletonModule = singletons -> singletons.bind(Service.class).to(ServiceImpl.class);
        SingletonModule eagerSingletonModule = singletons -> singletons.eagerly().bind(ServiceImpl.class);
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                install(singletonModule);
                                install(eagerSingletonModule);
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Service.class)));
        assertSame(EAGER_SINGLETON, scopeOf(injector.getBinding(ServiceImpl.class)));
    }

    @Test
    public void testSingletonModuleFactory()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                install(singletonModule(singletons -> {
                                    singletons.bind(Service.class).to(ServiceImpl.class);
                                    singletons.eagerly().bind(ServiceImpl.class);
                                }));
                            }
                        });

        assertSame(Scopes.SINGLETON, scopeOf(injector.getBinding(Service.class)));
        assertSame(EAGER_SINGLETON, scopeOf(injector.getBinding(ServiceImpl.class)));
        ElementSource source = (ElementSource) injector.getBinding(Service.class).getSource();
        // the binding's source is the caller's lambda, with or without stack traces
        assertContains(source.toString(), getClass().getName() + "$");
        // the installed module is the caller's lambda
        assertContains(source.getModuleClassNames().get(0), getClass().getName());
    }

    @Test
    public void testSingletonModuleFactoryReturnsModule()
    {
        SingletonModule module = singletons -> singletons.bind(ServiceImpl.class);
        assertSame(module, singletonModule(module));
        assertThrows(NullPointerException.class, () -> singletonModule(null));
    }

    @Test
    public void testSingletonModuleFactoryErrorSource()
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
                                        install(singletonModule(singletons -> singletons.bind(Service.class)));
                                    }
                                }));

        // the installed module is the caller's lambda
        assertContains(
                exception.getMessage(),
                true,
                "No implementation for SingletonBinderTest$Service was bound.",
                "SingletonBinderTest$",
                "$$Lambda");
        assertFalse(exception.getMessage().contains("SingletonModule$$Lambda"));
    }

    @Test
    public void testLambdaWithoutSingletonModuleFactoryIsUnscoped()
    {
        Injector injector =
                Guice.createInjector(
                        new AbstractModule()
                        {
                            @Override
                            protected void configure()
                            {
                                // a plain Module, so its binder is not a SingletonBinder
                                install(binder -> binder.bind(Service.class).to(ServiceImpl.class));
                            }
                        });

        assertNotSame(injector.getInstance(Service.class), injector.getInstance(Service.class));
    }

    @Test
    public void testModuleBindingSourceIsModule()
    {
        List<Element> elements =
                Elements.getElements(
                        new SingletonModule()
                        {
                            @Override
                            public void configure(SingletonBinder singletons)
                            {
                                singletons.bind(Service.class).to(ServiceImpl.class);
                                singletons.eagerly().bind(ServiceImpl.class);
                            }
                        });

        assertEquals(2, elements.size());
        for (Element element : elements) {
            assertContains(element.getSource().toString(), getDeclaringSourcePart(getClass()));
        }
    }

    private static Object scopeOf(Binding<?> binding)
    {
        return binding.acceptScopingVisitor(
                new DefaultBindingScopingVisitor<>()
                {
                    @Override
                    public Object visitEagerSingleton()
                    {
                        return EAGER_SINGLETON;
                    }

                    @Override
                    public Object visitScope(Scope scope)
                    {
                        return scope;
                    }
                });
    }

    @Retention(RUNTIME)
    @BindingAnnotation
    @interface Red {}

    interface Service {}

    public static class ServiceImpl
            implements Service
    {
        public ServiceImpl() {}
    }

    static class ServiceProvider
            implements Provider<Service>
    {
        @Override
        public Service get()
        {
            return new ServiceImpl();
        }
    }

    static class CountingProvider
            implements Provider<Integer>
    {
        private int count;

        @Override
        public Integer get()
        {
            return ++count;
        }
    }
}
