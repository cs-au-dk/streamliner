package dk.casa.streamliner.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ProcessUtils {
	public static int waitFor(Process process) throws IOException, InterruptedException {
		try(BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			StringBuilder sbuilder = new StringBuilder();
			String line;
			while((line = br.readLine()) != null) {
				sbuilder.append(line).append("\n");
			}

			return process.waitFor();
		}
	}
}
