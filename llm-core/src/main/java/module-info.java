module dev.kualu.agent {
    requires transitive java.net.http;
    requires jdk.httpserver;
    requires com.google.gson;
    requires org.slf4j;

    exports dev.kualu.http;
    exports dev.kualu.llm;
    exports dev.kualu.llm.client;
    exports dev.kualu.llm.memory;
    exports dev.kualu.llm.tools;
    exports dev.kualu.mcp;
    exports dev.kualu.mcp.auth;
    exports dev.kualu.mcp.config;
}
