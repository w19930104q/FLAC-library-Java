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
 * Top-level entry point: runs the shared contract and frame-decode/seek suite against
 * both implementations, then replays one identical randomized operation script across
 * the two to prove their observable behavior is equivalent. Exits non-zero on any failure.
 */
public final class FlacInputContractRunner {

	public static void main(String[] args) throws Exception {
		FlacInputFactory byteArray = data -> new ByteArrayFlacInput(data);
		FlacInputFactory seekableFile = data -> {
			Path path = Files.createTempFile("equivalence-flac-input-", ".bin");
			Files.write(path, data);
			path.toFile().deleteOnExit();
			return new SeekableFileFlacInput(path.toFile());
		};

		FlacInputContract.runAll("ByteArrayFlacInput", byteArray);
		FrameDecoderSeekTest.runAll("ByteArrayFlacInput", byteArray);

		FlacInputContract.runAll("SeekableFileFlacInput", seekableFile);
		FrameDecoderSeekTest.runAll("SeekableFileFlacInput", seekableFile);
		FlacDecoderIntegrationTest.run();

		FlacInputEquivalenceTest.run(byteArray, seekableFile);

		System.out.println();
		System.out.println("ALL SUITES PASSED (" + TestUtil.passedChecks + " total checks)");
	}


	private FlacInputContractRunner() {}

}
