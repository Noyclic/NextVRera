package com.noyclic.nextvrera.logcat;

interface ILogcatService {
    void destroy() = 16777114;
    String startCapture() = 1;
    String readChunk(int maxLines) = 2;
    void stopCapture() = 3;
}
