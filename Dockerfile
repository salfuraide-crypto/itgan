# Builds and runs the Itqan platform.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src ./src
RUN mkdir out && javac -encoding UTF-8 -d out $(find src -name "*.java")

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY web ./web
# PostgreSQL driver, used only when DATABASE_URL is set (keeps data on hosts with a temporary disk).
ADD https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar ./lib/postgresql.jar
ENV ITQAN_HOST=0.0.0.0 \
    ITQAN_DATA=/data \
    ITQAN_OPEN_BROWSER=0 \
    PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out:lib/postgresql.jar", "Main"]
