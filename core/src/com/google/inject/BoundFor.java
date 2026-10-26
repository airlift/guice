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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Qualifies a binding with a class, so that bindings of the same type can be told apart without
 * declaring a binding annotation for each of them. The class can be any class: usually the one the
 * binding is for, or the module that makes it. Create one to bind with {@link Binder#boundFor},
 * which as a static interface method is called through {@code Binder} or imported statically:
 *
 * <pre>
 *     import static com.google.inject.Binder.boundFor;
 *     . . .
 *     bind(HttpClient.class).annotatedWith(boundFor(GovernanceService.class)).toInstance(client);
 *
 *     {@literal @}Inject
 *     GovernanceService({@literal @}BoundFor(GovernanceService.class) HttpClient client) { ... }</pre>
 *
 * <p>Unlike {@link com.google.inject.name.Named @Named}, the qualifier is a class literal, so it is
 * checked by the compiler and followed by refactoring tools.
 *
 * <p>{@code @BoundFor} is matched exactly: an injection of
 * {@code @BoundFor(GovernanceService.class)} is only satisfied by a binding for that class. Binding
 * a key with {@code annotatedWith(BoundFor.class)}, which would otherwise act as a catch-all for
 * every {@code @BoundFor}, is an error.
 *
 * <p>Once a type has {@code @BoundFor} bindings, injecting it without a qualifier must be satisfied
 * by an explicit binding, as though {@link Binder#requireExplicitBindings()} applied to that type:
 * it is not created just in time. This reports a forgotten {@code @BoundFor} when the injector is
 * created, rather than injecting a new, unconfigured instance. The type can still be the target of a
 * linked binding, as in
 * {@code bind(Mapper.class).annotatedWith(boundFor(Billing.class)).to(Mapper.class)}.
 */
@Retention(RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@BindingAnnotation
public @interface BoundFor
{
    Class<?> value();
}
