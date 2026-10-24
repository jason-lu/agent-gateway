module dev.kualu.agent {
    requires transitive java.net.http;
    requires com.google.gson;

    exports dev.kualu.http;
    exports dev.kualu.llm;
}
