package com.noyclic.nextvrera.logcat;

interface ILogcatService {
    void destroy() = 16777114;
    String startCapture();
    String readChunk(int maxLines);
    void stopCapture();
}
