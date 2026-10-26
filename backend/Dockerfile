# Production image for the Waypoint Spring Boot backend (used by Render).
# Build: docker build -t waypoint-backend backend/

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Normalise line endings in case the repo was checked out on Windows.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && find target -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

FROM eclipse-temurin:21-jre
# Tesseract is used by TimesheetOcrService for scanned timesheet uploads.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home waypoint
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
RUN mkdir -p uploads && chown -R waypoint /app
USER waypoint

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
# Render injects PORT; fall back to the local default.
CMD ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8081} -jar app.jar"]
