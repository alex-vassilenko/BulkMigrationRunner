FROM openjdk:latest

COPY target/ target/
RUN chmod -R 777 target
WORKDIR /target

CMD [ "java", "-classpath", "migration-0.0.1.jar", "com.indellient.App" ]