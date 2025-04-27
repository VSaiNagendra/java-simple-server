FROM openjdk:17-slim
WORKDIR /app/codecrafters-http-server-java
COPY / /app/codecrafters-http-server-java

RUN javac -d out src/main/java/Main.java

CMD ["java", "-cp", "out", "Main"]
