/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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


/*
 * @test
 *
 * @summary converted from VM Testbase nsk/jdi/ClassType/invokeMethod/invokemethod012.
 * VM Testbase keywords: [quick, jpda, jdi]
 * VM Testbase readme:
 * DESCRIPTION
 *     This test checks that the single threaded invocation will be
 *     performed properly through the JDI method:
 *         com.sun.jdi.ClassType.invokeMethod().
 *     The following assertions are verified:
 *         - only the specified thread will be resumed with the flag
 *           INVOKE_SINGLE_THREADED;
 *         - upon completion of a single threaded invoke, the invoking
 *           thread will be suspended once again; any threads started
 *           during the single threaded invocation will not be suspended
 *           when the invocation completes.
 *     A debuggee part of the test starts several threads. The debugger
 *     calls the JDI method with the flag ClassType.INVOKE_SINGLE_THREADED.
 *     Then, during the invocation the debugger resumes some threads.
 *     Upon completing the invocation, these resumed threads are checked
 *     to be not suspended again.
 * COMMENTS
 *     Fixed TEST_BUG 6358778.
 *
 * @library /vmTestbase
 *          /test/lib
 * @build nsk.jdi.ClassType.invokeMethod.invokemethod012
 *        nsk.jdi.ClassType.invokeMethod.invokemethod012t
 * @run driver
 *      nsk.jdi.ClassType.invokeMethod.invokemethod012
 *      -verbose
 *      -includevirtualthreads
 *      -arch=${os.family}-${os.simpleArch}
 *      -waittime=5
 *      -debugee.vmkind=java
 *      -transport.address=dynamic
 *      -debugee.vmkeys="${test.vm.opts} ${test.java.opts}"
 */

