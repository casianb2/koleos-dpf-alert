package com.koleos.osm;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class CountingInputStream extends FilterInputStream {

    private long bytesRead = 0;

    public CountingInputStream(InputStream in) {
        super(in);
    }

    public long getBytesRead() {
        return bytesRead;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) bytesRead++;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) bytesRead += n;
        return n;
    }
}