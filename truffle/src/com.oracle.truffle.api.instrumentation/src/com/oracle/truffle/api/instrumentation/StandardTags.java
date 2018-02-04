/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.instrumentation;

import java.util.function.Function;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Set of standard tags usable by language agnostic tools. Language should {@link ProvidedTags
 * provide} an implementation of these tags in order to support a wide variety of tools.
 *
 * @since 0.12
 */
public final class StandardTags {

    private StandardTags() {
        /* No instances */
    }

    /**
     * Marks program locations that represent a statement of a language.
     * <p>
     * Use case descriptions:
     * <ul>
     * <li><b>Debugger:</b> Marks program locations where ordinary stepping should halt. The
     * debugger will halt just <em>before</em> a code location is executed that is marked with this
     * tag.
     * <p>
     * In most languages, this means statements are distinct from expressions and only one node
     * representing the statement should be tagged. Subexpressions are typically not tagged so that
     * for example a step-over operation will stop at the next independent statement to get the
     * desired behavior.</li>
     * </ul>
     *
     * @since 0.12
     */
    public static final class StatementTag extends Tag {
        private StatementTag() {
            /* No instances */
        }
    }

    /**
     * Marks program locations that represent a call to other guest language functions, methods or
     * closures.
     * <p>
     * Use case descriptions:
     * <ul>
     * <li><b>Debugger:</b> Marks program locations where <em>returning</em> or <em>stepping
     * out</em> from a method/procedure/closure call should halt. The debugger will halt at the code
     * location that has just executed the call that returned.</li>
     * </ul>
     *
     * @since 0.12
     */
    public static final class CallTag extends Tag {
        private CallTag() {
            /* No instances */
        }
    }

    /**
     * Marks program locations as root of a function, method or closure. The root prolog should be
     * executed by this node. In particular, when the implementation copies
     * {@link Frame#getArguments()} into {@link FrameSlot}s, it should do it here for the
     * instrumentation to work correctly.
     * <p>
     * Use case descriptions:
     * <ul>
     * <li><b>Profiler:</b> Marks every root that should be profiled.</li>
     * </ul>
     *
     * @since 0.12
     */
    public static final class RootTag extends Tag {

        /**
         * The root name associated with this tagged node. Delegates to {@link RootNode#getName()}
         * by default.
         */
        public static final Attribute<String> NAME = createAttribute(RootTag.class, "name", String.class, new DefaultName(), null);

        private RootTag() {
            /* No instances */
        }

        private static class DefaultName implements Function<Node, String> {

            public String apply(Node node) {
                RootNode root = node.getRootNode();
                if (root != null) {
                    return root.getName();
                }
                return null;
            }
        }
    }

    /**
     * Marks program locations as to be considered expressions of the languages. Common examples for
     * expressions are:
     * <ul>
     * <li>Literal expressions
     * <li>Arithmetic expressions like addition and multiplication
     * <li>Condition expressions
     * <li>Function calls
     * <li>Array, Object or variable reads and writes
     * <li>Instantiations
     * </ul>
     * Use case descriptions:
     * <ul>
     * <li><b>Coverage:</b> To compute expression coverage.</li>
     * <li><b>Debugger:</b> Fine grained debugging of expressions.</li>
     * </ul>
     *
     * @since 0.30
     */
    public static final class ExpressionTag extends Tag {

        private ExpressionTag() {
            /* No instances */
        }

    }

}
