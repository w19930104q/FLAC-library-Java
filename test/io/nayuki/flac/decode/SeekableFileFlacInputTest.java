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

import java.nio.file.Files;
import java.nio.file.Path;


/**
 * Runs the entire shared behavior suite against {@link SeekableFileFlacInput}.
 * Each fresh input is materialized as a temporary file so the stream reads
 * through a real {@link java.io.RandomAccessFile}. The scenarios themselves
 * are identical to those run for the byte-array implementation.
 */
public final class SeekableFileFlacInputTest {

	public static void main(String[] args) throws Exception {
		FlacInputFactory factory = data -> {
			Path path = Files.createTempFile("seekable-flac-input-", ".bin");
			Files.write(path, data);
			path.toFile().deleteOnExit();
			return new SeekableFileFlacInput(path.toFile());
		};
		FlacInputContract.runAll("SeekableFileFlacInput", factory);
		FrameDecoderSeekTest.runAll("SeekableFileFlacInput", factory);
		FlacDecoderIntegrationTest.run();
		System.out.println("SeekableFileFlacInput: ALL TESTS PASSED (" + TestUtil.passedChecks + " cumulative checks)");
	}


	private SeekableFileFlacInputTest() {}

}
