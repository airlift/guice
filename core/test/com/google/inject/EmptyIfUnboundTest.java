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

import com.google.inject.multibindings.MapBinder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.InjectionPoint;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static com.google.inject.Asserts.assertContains;
import static com.google.inject.name.Names.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmptyIfUnboundTest
{
    @Test
    public void testConstructorParameterEmptyWhenUnbound()
    {
        ConstructorInjected injected = Guice.createInjector().getInstance(ConstructorInjected.class);
        assertEquals(Optional.empty(), injected.service);
    }

    @Test
    public void testConstructorParameterPresentWhenBound()
    {
        Injector injector = Guice.createInjector(binder -> binder.bind(Service.class).to(RealService.class).in(Singleton.class));
        ConstructorInjected injected = injector.getInstance(ConstructorInjected.class);
        assertSame(injector.getInstance(Service.class), injected.service.orElseThrow());
    }

    @Test
    public void testFieldAndMethodParameter()
    {
        MembersInjected unbound = Guice.createInjector().getInstance(MembersInjected.class);
        assertEquals(Optional.empty(), unbound.field);
        assertEquals(Optional.empty(), unbound.fromMethod);

        MembersInjected bound =
                Guice.createInjector(binder -> binder.bind(Service.class).to(RealService.class))
                        .getInstance(MembersInjected.class);
        assertInstanceOf(RealService.class, bound.field.orElseThrow());
        assertInstanceOf(RealService.class, bound.fromMethod.orElseThrow());
    }

    @Test
    public void testInjectMembers()
    {
        MembersInjected instance = new MembersInjected();
        Guice.createInjector().injectMembers(instance);
        assertEquals(Optional.empty(), instance.field);
        assertEquals(Optional.empty(), instance.fromMethod);
    }

    @Test
    public void testProvidesMethodParameter()
    {
        Module providesModule =
                new AbstractModule()
                {
                    @Provides
                    String describe(@EmptyIfUnbound Optional<Service> service)
                    {
                        return service.map(Service::name).orElse("none");
                    }
                };

        assertEquals("none", Guice.createInjector(providesModule).getInstance(String.class));
        assertEquals(
                "real",
                Guice.createInjector(providesModule, binder -> binder.bind(Service.class).to(RealService.class))
                        .getInstance(String.class));
    }

    @Test
    public void testBindingAnnotationQualifiesValue()
    {
        Injector unbound = Guice.createInjector(binder -> binder.bind(Service.class).to(RealService.class));
        assertEquals(Optional.empty(), unbound.getInstance(NamedInjected.class).service);

        Injector bound =
                Guice.createInjector(
                        binder -> binder.bind(Service.class).annotatedWith(named("audit")).to(RealService.class));
        assertInstanceOf(RealService.class, bound.getInstance(NamedInjected.class).service.orElseThrow());
    }

    @Test
    public void testJustInTimeBindingIsPresent()
    {
        ConcreteInjected injected = Guice.createInjector().getInstance(ConcreteInjected.class);
        assertInstanceOf(RealService.class, injected.service.orElseThrow());
    }

    @Test
    public void testNoInjectableConstructorIsEmpty()
    {
        NoConstructorInjected injected = Guice.createInjector().getInstance(NoConstructorInjected.class);
        assertEquals(Optional.empty(), injected.value);
    }

    @Test
    public void testErrorInJustInTimeBindingIsReported()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(binder -> binder.bind(BrokenInjected.class)));
        assertContains(exception.getMessage(), "No implementation for EmptyIfUnboundTest$Service was bound.");
    }

    @Test
    public void testErrorInImplementedByTargetIsReported()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(binder -> binder.bind(ImplementedByInjected.class)));
        assertContains(exception.getMessage(), "No implementation for EmptyIfUnboundTest$Service was bound.");
    }

    @Test
    public void testRequireExplicitBindings()
    {
        Injector unbound =
                Guice.createInjector(
                        binder -> {
                            binder.requireExplicitBindings();
                            binder.bind(ConcreteInjected.class);
                        });
        assertEquals(Optional.empty(), unbound.getInstance(ConcreteInjected.class).service);

        Injector bound =
                Guice.createInjector(
                        binder -> {
                            binder.requireExplicitBindings();
                            binder.bind(ConcreteInjected.class);
                            binder.bind(RealService.class);
                        });
        assertInstanceOf(RealService.class, bound.getInstance(ConcreteInjected.class).service.orElseThrow());
    }

    @Test
    public void testRequireAtInjectOnConstructors()
    {
        Injector injector =
                Guice.createInjector(
                        binder -> {
                            binder.requireAtInjectOnConstructors();
                            binder.bind(ConcreteInjected.class);
                        });
        assertEquals(Optional.empty(), injector.getInstance(ConcreteInjected.class).service);
    }

    @Test
    public void testParentInjectorBinding()
    {
        Injector parent = Guice.createInjector(binder -> binder.bind(Service.class).to(RealService.class));
        Injector child = parent.createChildInjector();
        assertInstanceOf(RealService.class, child.getInstance(ConstructorInjected.class).service.orElseThrow());
    }

    @Test
    public void testOptionalBinderTakesPrecedence()
    {
        // The OptionalBinder has no value, so Optional<RealService> is empty even though RealService
        // could be bound just in time
        Injector absent =
                Guice.createInjector(binder -> OptionalBinder.newOptionalBinder(binder, RealService.class));
        assertEquals(Optional.empty(), absent.getInstance(ConcreteInjected.class).service);

        RealService service = new RealService();
        Injector present =
                Guice.createInjector(
                        binder -> OptionalBinder.newOptionalBinder(binder, RealService.class).setDefault().toInstance(service));
        assertSame(service, present.getInstance(ConcreteInjected.class).service.orElseThrow());
    }

    @Test
    public void testNullValueIsEmpty()
    {
        Injector injector = Guice.createInjector(binder -> binder.bind(Service.class).toProvider(() -> null));
        assertEquals(Optional.empty(), injector.getInstance(ConstructorInjected.class).service);
    }

    @Test
    public void testProviderOfValue()
    {
        assertEquals(Optional.empty(), Guice.createInjector().getInstance(ProviderInjected.class).service);

        Injector bound = Guice.createInjector(binder -> binder.bind(Service.class).to(RealService.class));
        Provider<Service> provider = bound.getInstance(ProviderInjected.class).service.orElseThrow();
        assertInstanceOf(RealService.class, provider.get());
    }

    @Test
    public void testProvisionErrorInValue()
    {
        Injector injector =
                Guice.createInjector(
                        binder -> binder.bind(Service.class).toProvider(() -> {
                            throw new IllegalStateException("boom");
                        }));
        ProvisionException exception =
                assertThrows(ProvisionException.class, () -> injector.getInstance(ConstructorInjected.class));
        assertContains(exception.getMessage(), "boom", "while locating EmptyIfUnboundTest$Service");
    }

    @Test
    public void testPrimitiveOptionals()
    {
        PrimitiveInjected unbound = Guice.createInjector().getInstance(PrimitiveInjected.class);
        assertEquals(OptionalInt.empty(), unbound.port);
        assertEquals(OptionalLong.empty(), unbound.timeout);
        assertEquals(OptionalDouble.empty(), unbound.ratio);

        PrimitiveInjected bound =
                Guice.createInjector(
                                binder -> {
                                    binder.bind(Integer.class).annotatedWith(named("port")).toInstance(8080);
                                    binder.bind(long.class).annotatedWith(named("timeout")).toInstance(30L);
                                    binder.bind(Double.class).annotatedWith(named("ratio")).toInstance(0.5);
                                })
                        .getInstance(PrimitiveInjected.class);
        assertEquals(OptionalInt.of(8080), bound.port);
        assertEquals(OptionalLong.of(30), bound.timeout);
        assertEquals(OptionalDouble.of(0.5), bound.ratio);
    }

    @Test
    public void testPrimitiveOptionalsFromConstants()
    {
        PrimitiveInjected injected =
                Guice.createInjector(
                                binder -> {
                                    binder.bindConstant().annotatedWith(named("port")).to("8080");
                                    binder.bindConstant().annotatedWith(named("timeout")).to("30");
                                    binder.bindConstant().annotatedWith(named("ratio")).to("0.5");
                                })
                        .getInstance(PrimitiveInjected.class);
        assertEquals(OptionalInt.of(8080), injected.port);
        assertEquals(OptionalLong.of(30), injected.timeout);
        assertEquals(OptionalDouble.of(0.5), injected.ratio);
    }

    @Test
    public void testPrimitiveOptionalNullValueIsEmpty()
    {
        Injector injector =
                Guice.createInjector(binder -> binder.bind(Integer.class).annotatedWith(named("port")).toProvider(() -> null));
        assertEquals(OptionalInt.empty(), injector.getInstance(PrimitiveInjected.class).port);
    }

    @Test
    public void testPrimitiveOptionalBindingTakesPrecedence()
    {
        Injector injector =
                Guice.createInjector(
                        binder -> {
                            binder.bind(OptionalInt.class).annotatedWith(named("port")).toInstance(OptionalInt.of(1));
                            binder.bind(Integer.class).annotatedWith(named("port")).toInstance(8080);
                        });
        assertEquals(OptionalInt.of(1), injector.getInstance(PrimitiveInjected.class).port);
    }

    @Test
    public void testRequiresSupportedType()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(binder -> binder.bind(NotOptionalInjected.class)));
        assertContains(
                exception.getMessage(),
                "@EmptyIfUnbound requires an Optional of a fully specified type, OptionalInt, OptionalLong,"
                        + " OptionalDouble, Set, Collection or Map, but the injected type is String.");

        exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(binder -> binder.bind(ListInjected.class)));
        assertContains(exception.getMessage(), "but the injected type is List<EmptyIfUnboundTest$Service>.");
    }

    @Test
    public void testMultibinderSet()
    {
        SetInjected unbound = Guice.createInjector().getInstance(SetInjected.class);
        assertEquals(Set.of(), unbound.services);
        assertEquals(Set.of(), unbound.extendsServices);
        assertEquals(List.of(), List.copyOf(unbound.providers));
        assertThrows(UnsupportedOperationException.class, () -> unbound.services.add(new RealService()));

        RealService service = new RealService();
        SetInjected bound =
                Guice.createInjector(binder -> Multibinder.newSetBinder(binder, Service.class).addBinding().toInstance(service))
                        .getInstance(SetInjected.class);
        assertEquals(Set.of(service), bound.services);
        assertEquals(Set.of(service), bound.extendsServices);
        assertSame(service, bound.providers.iterator().next().get());
    }

    @Test
    public void testAnnotatedMultibinder()
    {
        RealService service = new RealService();
        Injector unannotated =
                Guice.createInjector(binder -> Multibinder.newSetBinder(binder, Service.class).addBinding().toInstance(service));
        assertEquals(Set.of(), unannotated.getInstance(NamedSetInjected.class).services);

        Injector annotated =
                Guice.createInjector(
                        binder -> Multibinder.newSetBinder(binder, Service.class, named("audit")).addBinding().toInstance(service));
        assertEquals(Set.of(service), annotated.getInstance(NamedSetInjected.class).services);
    }

    @Test
    public void testMapBinder()
    {
        MapInjected unbound = Guice.createInjector().getInstance(MapInjected.class);
        assertEquals(Map.of(), unbound.services);
        assertEquals(Map.of(), unbound.providers);
        assertEquals(Map.of(), unbound.multimap);
        assertThrows(UnsupportedOperationException.class, () -> unbound.services.put("real", new RealService()));

        RealService service = new RealService();
        MapInjected bound =
                Guice.createInjector(
                                binder -> MapBinder.newMapBinder(binder, String.class, Service.class)
                                        .permitDuplicates()
                                        .addBinding("real")
                                        .toInstance(service))
                        .getInstance(MapInjected.class);
        assertEquals(Map.of("real", service), bound.services);
        assertSame(service, bound.providers.get("real").get());
        assertEquals(Map.of("real", Set.of(service)), bound.multimap);
    }

    @Test
    public void testCollectionInProvidesMethod()
    {
        Module providesModule =
                new AbstractModule()
                {
                    @Provides
                    String describe(@EmptyIfUnbound Set<Service> services)
                    {
                        return services.size() + " services";
                    }
                };

        assertEquals("0 services", Guice.createInjector(providesModule).getInstance(String.class));
        assertEquals(
                "1 services",
                Guice.createInjector(
                                providesModule,
                                binder -> Multibinder.newSetBinder(binder, Service.class).addBinding().to(RealService.class))
                        .getInstance(String.class));
    }

    @Test
    public void testOptionalOfCollection()
    {
        assertEquals(Optional.empty(), Guice.createInjector().getInstance(OptionalSetInjected.class).services);

        Injector declared = Guice.createInjector(binder -> Multibinder.newSetBinder(binder, Service.class));
        assertEquals(Optional.of(Set.of()), declared.getInstance(OptionalSetInjected.class).services);
    }

    @Test
    public void testRequiresFullySpecifiedOptional()
    {
        CreationException exception =
                assertThrows(
                        CreationException.class,
                        () -> Guice.createInjector(binder -> binder.bind(WildcardInjected.class)));
        assertContains(exception.getMessage(), "@EmptyIfUnbound requires an Optional of a fully specified type");
    }

    @Test
    public void testDependency()
    {
        List<Dependency<?>> dependencies = InjectionPoint.forConstructorOf(MixedInjected.class).getDependencies();
        assertTrue(dependencies.get(0).isEmptyIfUnbound());
        assertEquals(new Key<Optional<Service>>() {}, dependencies.get(0).getKey());
        assertFalse(dependencies.get(1).isEmptyIfUnbound());
        assertFalse(Dependency.get(Key.get(Service.class)).isEmptyIfUnbound());

        Dependency<?> portDependency = InjectionPoint.forConstructorOf(PrimitiveInjected.class).getDependencies().get(0);
        assertTrue(portDependency.isEmptyIfUnbound());
        assertEquals(Key.get(OptionalInt.class, named("port")), portDependency.getKey());
        Dependency<?> setDependency = InjectionPoint.forConstructorOf(NamedSetInjected.class).getDependencies().get(0);
        assertTrue(setDependency.isEmptyIfUnbound());
        assertEquals(new Key<Set<Service>>(named("audit")) {}, setDependency.getKey());
    }

    interface Service
    {
        String name();
    }

    static class RealService
            implements Service
    {
        @Override
        public String name()
        {
            return "real";
        }
    }

    static class ConstructorInjected
    {
        final Optional<Service> service;

        @Inject
        ConstructorInjected(@EmptyIfUnbound Optional<Service> service)
        {
            this.service = service;
        }
    }

    static class MembersInjected
    {
        @Inject
        @EmptyIfUnbound
        Optional<Service> field;

        Optional<Service> fromMethod;

        @Inject
        void setService(@EmptyIfUnbound Optional<Service> service)
        {
            this.fromMethod = service;
        }
    }

    static class NamedInjected
    {
        final Optional<Service> service;

        @Inject
        NamedInjected(@EmptyIfUnbound @Named("audit") Optional<Service> service)
        {
            this.service = service;
        }
    }

    static class ConcreteInjected
    {
        final Optional<RealService> service;

        @Inject
        ConcreteInjected(@EmptyIfUnbound Optional<RealService> service)
        {
            this.service = service;
        }
    }

    static class NoInjectableConstructor
    {
        NoInjectableConstructor(String value) {}
    }

    static class NoConstructorInjected
    {
        final Optional<NoInjectableConstructor> value;

        @Inject
        NoConstructorInjected(@EmptyIfUnbound Optional<NoInjectableConstructor> value)
        {
            this.value = value;
        }
    }

    static class NeedsService
            implements ImplementedByNeedsService
    {
        @Inject
        NeedsService(Service service) {}
    }

    static class BrokenInjected
    {
        @Inject
        BrokenInjected(@EmptyIfUnbound Optional<NeedsService> needsService) {}
    }

    @ImplementedBy(NeedsService.class)
    interface ImplementedByNeedsService {}

    static class ImplementedByInjected
    {
        @Inject
        ImplementedByInjected(@EmptyIfUnbound Optional<ImplementedByNeedsService> value) {}
    }

    static class ProviderInjected
    {
        final Optional<Provider<Service>> service;

        @Inject
        ProviderInjected(@EmptyIfUnbound Optional<Provider<Service>> service)
        {
            this.service = service;
        }
    }

    static class PrimitiveInjected
    {
        final OptionalInt port;
        final OptionalLong timeout;
        final OptionalDouble ratio;

        @Inject
        PrimitiveInjected(
                @EmptyIfUnbound @Named("port") OptionalInt port,
                @EmptyIfUnbound @Named("timeout") OptionalLong timeout,
                @EmptyIfUnbound @Named("ratio") OptionalDouble ratio)
        {
            this.port = port;
            this.timeout = timeout;
            this.ratio = ratio;
        }
    }

    static class NotOptionalInjected
    {
        @Inject
        NotOptionalInjected(@EmptyIfUnbound String value) {}
    }

    static class ListInjected
    {
        @Inject
        ListInjected(@EmptyIfUnbound List<Service> services) {}
    }

    static class SetInjected
    {
        final Set<Service> services;
        final Set<? extends Service> extendsServices;
        final Collection<Provider<Service>> providers;

        @Inject
        SetInjected(
                @EmptyIfUnbound Set<Service> services,
                @EmptyIfUnbound Set<? extends Service> extendsServices,
                @EmptyIfUnbound Collection<Provider<Service>> providers)
        {
            this.services = services;
            this.extendsServices = extendsServices;
            this.providers = providers;
        }
    }

    static class NamedSetInjected
    {
        final Set<Service> services;

        @Inject
        NamedSetInjected(@EmptyIfUnbound @Named("audit") Set<Service> services)
        {
            this.services = services;
        }
    }

    static class MapInjected
    {
        @Inject
        @EmptyIfUnbound
        Map<String, Service> services;

        @Inject
        @EmptyIfUnbound
        Map<String, Provider<Service>> providers;

        @Inject
        @EmptyIfUnbound
        Map<String, Set<Service>> multimap;
    }

    static class OptionalSetInjected
    {
        final Optional<Set<Service>> services;

        @Inject
        OptionalSetInjected(@EmptyIfUnbound Optional<Set<Service>> services)
        {
            this.services = services;
        }
    }

    static class WildcardInjected
    {
        @Inject
        WildcardInjected(@EmptyIfUnbound Optional<? extends Service> service) {}
    }

    static class MixedInjected
    {
        @Inject
        MixedInjected(@EmptyIfUnbound Optional<Service> service, String name) {}
    }
}
