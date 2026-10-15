package edu.campusbite;

import java.nio.file.Path;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

public final class CampusBiteApplication {
    private CampusBiteApplication() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path webApp = Path.of("target", "campusbite").toAbsolutePath();

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(Path.of("target", "tomcat").toString());
        tomcat.setPort(port);
        Connector connector = tomcat.getConnector();
        connector.setProperty("address", "0.0.0.0");
        Context context = tomcat.addWebapp("", webApp.toString());
        context.setParentClassLoader(CampusBiteApplication.class.getClassLoader());

        tomcat.start();
        System.out.println("CampusBite is running at http://localhost:" + port + "/");
        tomcat.getServer().await();
    }
}
