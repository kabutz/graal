/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.strings.bench;

import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.oracle.truffle.api.strings.TruffleString;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class CompareBenchmark extends TStringBenchmarkBase {

    public static final TruffleString.Encoding ENCODING = TruffleString.Encoding.UTF_16;

    @State(Scope.Benchmark)
    public static class BenchState {

        @Param({"64"}) int length;
        // Checkstyle: stop
        String str = "NoahLiamJacobMasonWilliamEthanMichaelAlexanderJaydenDanielElijahAidenJamesBenjaminMatthewJacksonLoganDavidAnthonyJosephJoshuaAndrewLucasGabrielSamuelChristopherJohnDylanIsaacRyanNathanCarterCalebLukeChristianHunterHenryOwenLandonJackWyattJonathanEliIsaiahSebastianJaxonBraydenGavinLeviAaronOliverJordanNicholasEvanConnorCharlesJeremiahCameronAdrianThomasRobertTylerColtonAustinJaceAngelDominicJosiahBrandonAydenKevinZacharyParkerBlakeJoseChaseGraysonJasonIanBentleyAdamXavierCooperJustinNolanHudsonEastonJaseCarsonNathanielJaxsonKaydenBrodyLincolnLuisTristanJulianDamianCamdenJuan";
        // Checkstyle: resume
        TruffleString a = TruffleString.fromJavaStringUncached(str, TruffleString.Encoding.UTF_16);
        TruffleString b;
        Context context;
        Value compare;

        public BenchState() {
        }

        @Setup
        public void setUp() {
            context = Context.newBuilder(TStringTestDummyLanguage.ID).build();
            context.enter();
            compare = context.parse(TStringTestDummyLanguage.ID, "compareTString");
            b = createDiff(a, length);
        }

        @TearDown
        public void tearDown() {
            context.leave();
            context.close();
        }
    }

    private static TruffleString createDiff(TruffleString s, int pos) {
        int charPos = pos < 0 ? s.byteLength(ENCODING) + (pos * 2) : pos * 2;
        return s.substringByteIndexUncached(0, charPos, ENCODING, true).concatUncached(
                        TruffleString.fromCodePointUncached('!', ENCODING), ENCODING, false).concatUncached(
                                        s.substringByteIndexUncached(charPos, s.byteLength(ENCODING) - charPos, ENCODING, true), ENCODING, false);
    }

    @Benchmark
    public Value compare(BenchState state) {
        return state.compare.execute(state.a, state.b);
    }
}
