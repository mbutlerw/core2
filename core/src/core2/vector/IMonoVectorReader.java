package core2.vector;

import org.apache.arrow.memory.ArrowBuf;

import java.nio.ByteBuffer;

public interface IMonoVectorReader {

    boolean readBoolean(int idx);
    byte readByte(int idx);
    short readShort(int idx);
    int readInt(int idx);
    long readLong(int idx);

    float readFloat(int idx);
    double readDouble(int idx);

    ByteBuffer readBuffer(int idx);
    Object readObject(int idx);
}
