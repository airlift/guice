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

package com.google.inject.internal.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Looks up line numbers for classes and their members, using the JDK's class-file API rather than
 * a bytecode library, so error-message line attribution carries no extra dependency.
 *
 * @author Chris Nokleberg
 */
final class LineNumbers
{
    private static final Logger logger = Logger.getLogger(LineNumbers.class.getName());
    private static volatile boolean alreadyLoggedReadingFailure;

    private final Class<?> type;
    private final Map<String, Integer> lines = new HashMap<>();
    private String source;
    private int firstLine = Integer.MAX_VALUE;

    /**
     * Reads line number information from the given class, if available.
     *
     * @param type the class to read line number information from
     */
    public LineNumbers(Class<?> type)
            throws IOException
    {
        this.type = type;

        if (!type.isArray()) {
            InputStream in = null;
            try {
                in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class");
            }
            catch (IllegalStateException ignored) {
                // Some classloaders throw IllegalStateException when they can't load a resource.
            }
            if (in != null) {
                try {
                    read(ClassFile.of().parse(in.readAllBytes()));
                }
                catch (Exception ignored) {
                    // We may be trying to inspect class files this JDK's parser rejects. If that
                    // happens, just ignore the class and don't capture line numbers. But log the
                    // failure so folks know something's off. (Only log it once, though, to avoid
                    // spam. It's OK if concurrent access makes this happen more than once.)
                    if (!alreadyLoggedReadingFailure) {
                        alreadyLoggedReadingFailure = true;
                        logger.log(
                                Level.WARNING,
                                "Failed loading line numbers. Further failures won't be logged.",
                                ignored);
                    }
                }
                finally {
                    try {
                        in.close();
                    }
                    catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void read(ClassModel classModel)
    {
        classModel
                .findAttribute(Attributes.sourceFile())
                .ifPresent(attribute -> source = attribute.sourceFile().stringValue());

        String internalName = classModel.thisClass().asInternalName();
        for (MethodModel method : classModel.methods()) {
            if ((method.flags().flagsMask() & ClassFile.ACC_PRIVATE) != 0) {
                continue;
            }
            String methodKey = method.methodName().stringValue() + method.methodType().stringValue();
            method.code()
                    .ifPresent(
                            code -> {
                                boolean methodRecorded = false;
                                int currentLine = -1;
                                for (CodeElement element : code) {
                                    if (element instanceof LineNumber lineNumber) {
                                        currentLine = lineNumber.line();
                                        if (currentLine < firstLine) {
                                            firstLine = currentLine;
                                        }
                                        if (!methodRecorded) {
                                            lines.put(methodKey, currentLine);
                                            methodRecorded = true;
                                        }
                                    }
                                    else if (element instanceof FieldInstruction field
                                            && field.opcode() == Opcode.PUTFIELD
                                            && currentLine != -1
                                            && internalName.equals(field.owner().asInternalName())
                                            && !lines.containsKey(field.name().stringValue())) {
                                        lines.put(field.name().stringValue(), currentLine);
                                    }
                                }
                            });
        }
    }

    /**
     * Get the source file name as read from the bytecode.
     *
     * @return the source file name if available, or null
     */
    public String getSource()
    {
        return source;
    }

    /**
     * Get the line number associated with the given member.
     *
     * @param member a field, constructor, or method belonging to the class used during construction
     * @return the wrapped line number, or null if not available
     * @throws IllegalArgumentException if the member does not belong to the class used during
     *         construction
     */
    public Integer getLineNumber(Member member)
    {
        checkArgument(
                type == member.getDeclaringClass(),
                "Member %s belongs to %s, not %s",
                member,
                member.getDeclaringClass(),
                type);
        return lines.get(memberKey(member));
    }

    /**
     * Gets the first line number.
     */
    public int getFirstLine()
    {
        return firstLine == Integer.MAX_VALUE ? 1 : firstLine;
    }

    private String memberKey(Member member)
    {
        requireNonNull(member, "member");
        if (member instanceof Field) {
            return member.getName();
        }
        else if (member instanceof Method method) {
            return method.getName()
                    + MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
        }
        else if (member instanceof Constructor<?> constructor) {
            StringBuilder sb = new StringBuilder().append("<init>(");
            for (Class<?> param : constructor.getParameterTypes()) {
                sb.append(param.descriptorString());
            }
            return sb.append(")V").toString();
        }
        else {
            throw new IllegalArgumentException(
                    "Unsupported implementation class for Member, " + member.getClass());
        }
    }
}
