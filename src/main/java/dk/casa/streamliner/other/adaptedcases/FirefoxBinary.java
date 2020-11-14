package dk.casa.streamliner.other.adaptedcases;
// Source: https://github.com/SeleniumHQ/selenium/blob/master/java/client/src/org/openqa/selenium/firefox/FirefoxBinary.java

import dk.casa.streamliner.stream.PushStream;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class FirefoxBinary {
	public static void main(String[] args) {
		locateFirefoxBinariesFromPlatform().forEach(System.out::println);
	}

	/**
	 * Locates the firefox binary by platform.
	 */
	private static PushStream<File> locateFirefoxBinariesFromPlatform() {
		List<File> executables = new ArrayList<>();

		// Stripped

		// Adapted Stream.of to Stream over collection
		List<String> strings = Arrays.asList(
				"Mozilla Firefox\\firefox.exe",
				"Firefox Developer Edition\\firefox.exe",
				"Nightly\\firefox.exe");

		executables.addAll(
				PushStream.of(strings)
						.map(FirefoxBinary::getPathsInProgramFiles)
						.flatMap(PushStream::of) // adapted lambda method
						.map(File::new).filter(File::exists)
						.collect(Collectors.toList()));

		// Stripped

		return PushStream.of(executables);
	}

	private static List<String> getPathsInProgramFiles(final String childPath) {
		return PushStream.of(Arrays.asList(getProgramFilesPath(), getProgramFiles86Path()))
				.map(parent -> new File(parent, childPath).getAbsolutePath())
				.collect(Collectors.toList());
	}

	/**
	 * Returns the path to the Windows Program Files. On non-English versions, this is not necessarily
	 * "C:\Program Files".
	 *
	 * @return the path to the Windows Program Files
	 */
	private static String getProgramFilesPath() {
		return getEnvVarPath("ProgramFiles", "C:\\Program Files").replace(" (x86)", "");
	}

	private static String getProgramFiles86Path() {
		return getEnvVarPath("ProgramFiles(x86)", "C:\\Program Files (x86)");
	}

	private static String getEnvVarPath(final String envVar, final String defaultValue) {
		return getEnvVarIgnoreCase(envVar)
				.map(File::new).filter(File::exists).map(File::getAbsolutePath)
				.orElseGet(() -> new File(defaultValue).getAbsolutePath());
	}

	private static Optional<String> getEnvVarIgnoreCase(String var) {
		// adapted entrySet.stream to PushStream
		return PushStream.of(System.getenv().entrySet())
				.filter(e -> e.getKey().equalsIgnoreCase(var))
				.findFirst().map(Map.Entry::getValue);
	}
}
