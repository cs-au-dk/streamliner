package dk.casa.streamliner.utils;

import java.io.IOException;
import java.io.OutputStreamWriter;

public interface Dotable {
	String toDot(String label);

	default void showDot(String label) {
		String dot = toDot(label);
		try {
			Process process = new ProcessBuilder("xdot", "-").start();
			OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream());
			//System.out.println(dot);
			writer.write(dot);
			writer.close();

			ProcessUtils.waitFor(process);
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}
}
