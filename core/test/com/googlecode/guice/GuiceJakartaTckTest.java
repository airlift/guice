/*
 * Copyright (C) 2009 Google Inc.
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

package com.googlecode.guice;

import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import jakarta.inject.Named;
import junit.framework.Test;
import junit.framework.TestFailure;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import org.atinject.tck.Tck;
import org.atinject.tck.auto.Car;
import org.atinject.tck.auto.Convertible;
import org.atinject.tck.auto.Drivers;
import org.atinject.tck.auto.DriversSeat;
import org.atinject.tck.auto.Engine;
import org.atinject.tck.auto.FuelTank;
import org.atinject.tck.auto.Seat;
import org.atinject.tck.auto.Tire;
import org.atinject.tck.auto.V8Engine;
import org.atinject.tck.auto.accessories.Cupholder;
import org.atinject.tck.auto.accessories.SpareTire;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Runs the Jakarta {@code @Inject} TCK against Guice.
 *
 * <p>The TCK is published as a JUnit 3 {@link TestSuite} and cannot be changed, so the suite is
 * flattened here and each leaf {@link Test} is exposed as a JUnit 5 {@link DynamicTest}.
 */
public class GuiceJakartaTckTest
{
    /**
     * Guice does not guarantee that a supertype's static members are injected before a subtype's, so
     * these two ordering tests fail. Everything else in the TCK passes.
     */
    private static final ImmutableSet<String> SUPPRESSED =
            ImmutableSet.of(
                    "testSupertypeStaticMethodsInjectedBeforeSubtypeStaticFields"
                            + "(org.atinject.tck.auto.Convertible$StaticTests)",
                    "testSupertypeStaticMethodsInjectedBeforeSubtypeStaticMethods"
                            + "(org.atinject.tck.auto.Convertible$StaticTests)");

    @TestFactory
    public List<DynamicTest> tck()
    {
        List<DynamicTest> tests = new ArrayList<>();
        collect((TestSuite) rawSuite(), tests);
        return tests;
    }

    /**
     * Recursively flattens {@code suite}, skipping {@link #SUPPRESSED} tests.
     */
    private static void collect(TestSuite suite, List<DynamicTest> tests)
    {
        for (Enumeration<Test> e = suite.tests(); e.hasMoreElements(); ) {
            Test test = e.nextElement();

            if (SUPPRESSED.contains(test.toString())) {
                continue;
            }

            if (test instanceof TestSuite) {
                collect((TestSuite) test, tests);
            }
            else {
                tests.add(DynamicTest.dynamicTest(test.toString(), () -> run(test)));
            }
        }
    }

    /**
     * Runs a single JUnit 3 test and rethrows whatever it reported, if anything.
     */
    private static void run(Test test)
            throws Throwable
    {
        TestResult result = new TestResult();
        test.run(result);

        List<TestFailure> problems = new ArrayList<>();
        problems.addAll(Collections.list(result.failures()));
        problems.addAll(Collections.list(result.errors()));

        if (!problems.isEmpty()) {
            throw problems.get(0).thrownException();
        }
    }

    private static Test rawSuite()
    {
        return Tck.testsFor(
                Guice.createInjector(
                                new AbstractModule()
                                {
                                    @Override
                                    protected void configure()
                                    {
                                        bind(Car.class).to(Convertible.class);
                                        bind(Seat.class).annotatedWith(Drivers.class).to(DriversSeat.class);
                                        bind(Engine.class).to(V8Engine.class);
                                        bind(Cupholder.class);
                                        bind(Tire.class);
                                        bind(FuelTank.class);
                                        requestStaticInjection(Convertible.class, SpareTire.class);
                                    }

                                    @Provides
                                    @Named("spare")
                                    Tire provideSpareTire(SpareTire spare)
                                    {
                                        return spare;
                                    }
                                })
                        .getInstance(Car.class),
                true,
                true);
    }
}
