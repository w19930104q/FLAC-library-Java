/*
 * FLAC library (Java)
 *
 * Copyright (c) Project Nayuki
 * https://www.nayuki.io/page/flac-library-java
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program (see COPYING.txt and COPYING.LESSER.txt).
 * If not, see <http://www.gnu.org/licenses/>.
 */

package io.nayuki.flac.decode;


/**
 * Runs the entire shared behavior suite against {@link ByteArrayFlacInput}.
 * The scenarios themselves live in {@link FlacInputContract} and
 * {@link FrameDecoderSeekTest} and are identical for every implementation.
 */
public final class ByteArrayFlacInputTest {

	public static void main(String[] args) throws Exception {
		FlacInputFactory factory = data -> new ByteArrayFlacInput(data);
		FlacInputContract.runAll("ByteArrayFlacInput", factory);
		FrameDecoderSeekTest.runAll("ByteArrayFlacInput", factory);
		System.out.println("ByteArrayFlacInput: ALL TESTS PASSED (" + TestUtil.passedChecks + " cumulative checks)");
	}


	private ByteArrayFlacInputTest() {}

}
