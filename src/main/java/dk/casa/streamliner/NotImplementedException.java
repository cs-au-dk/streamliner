package dk.casa.streamliner;

public class NotImplementedException extends RuntimeException {
	public NotImplementedException() { super("Not implemented"); }
	public NotImplementedException(String detail) { super(detail); }
}
