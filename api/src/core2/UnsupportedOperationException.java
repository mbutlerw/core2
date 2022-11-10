package core2;

import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;

@SuppressWarnings("unused")
public class UnsupportedOperationException extends java.lang.UnsupportedOperationException implements IExceptionInfo {

    private static final long serialVersionUID = -5699135465845314453L;

    private final IPersistentMap data;

    public UnsupportedOperationException(String message, IPersistentMap data, Throwable cause) {
        super(message, cause);
        this.data = data;
    }

    @Override
    public IPersistentMap getData() {
        return data;
    }
}
