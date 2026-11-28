package com.noyclic.nextvrera.logcat;

interface ILogcatService {
    void destroy();
    String startCapture();
    String readChunk(int maxLines);
    void stopCapture();
}
