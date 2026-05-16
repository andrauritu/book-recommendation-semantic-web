import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class BookRdfServer {
    private static final String EX = "http://example.org/books#";
    private static final Resource BOOK = ResourceFactory.createResource(EX + "Book");
    private static final Resource THEME = ResourceFactory.createResource(EX + "Theme");
    private static final Resource READING_LEVEL = ResourceFactory.createResource(EX + "ReadingLevel");
    private static final Property HAS_THEME = ResourceFactory.createProperty(EX + "hasTheme");
    private static final Property SUITABLE_FOR = ResourceFactory.createProperty(EX + "suitableFor");

    private static final Model model = ModelFactory.createDefaultModel();
    private static final VectorStore vectorStore = new VectorStore();

    public static void main(String[] args) throws IOException {
        loadSampleModel();
        vectorStore.load(Path.of("chatbot-books.xml"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", BookRdfServer::serveIndex);
        server.createContext("/api/sample", exchange -> handle(exchange, BookRdfServer::loadSample));
        server.createContext("/api/upload", exchange -> handle(exchange, BookRdfServer::uploadRdf));
        server.createContext("/api/add-book", exchange -> handle(exchange, BookRdfServer::addBook));
        server.createContext("/api/change-level", exchange -> handle(exchange, BookRdfServer::changeLevel));
        server.createContext("/api/rdf", exchange -> handle(exchange, BookRdfServer::sendRdfXml));
        server.createContext("/api/books", exchange -> handle(exchange, BookRdfServer::listBooks));
        server.createContext("/api/book", exchange -> handle(exchange, BookRdfServer::bookDetails));
        server.createContext("/api/chat", exchange -> handle(exchange, BookRdfServer::chat));
        server.createContext("/api/chat/starters", exchange -> handle(exchange, BookRdfServer::chatStarters));
        server.start();

        System.out.println("Open http://127.0.0.1:8080/index.html");
    }

    private static void handle(HttpExchange exchange, Handler handler) throws IOException {
        try {
            handler.run(exchange);
        } catch (Exception error) {
            send(exchange, 500, "text/plain", error.getMessage());
        }
    }

    private static void serveIndex(HttpExchange exchange) throws IOException {
        Path requested = Path.of(exchange.getRequestURI().getPath());
        String fileName = requested.toString().equals("\\") || requested.toString().equals("/") ? "index.html" : requested.getFileName().toString();
        Path file = Path.of(fileName);

        if (!Files.exists(file)) {
            send(exchange, 404, "text/plain", "File not found");
            return;
        }

        String contentType = contentType(fileName);
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String contentType(String fileName) {
        if (fileName.endsWith(".html")) {
            return "text/html";
        }

        if (fileName.endsWith(".css")) {
            return "text/css";
        }

        if (fileName.endsWith(".js")) {
            return "application/javascript";
        }

        if (fileName.endsWith(".xml") || fileName.endsWith(".rdf") || fileName.endsWith(".owl")) {
            return "application/xml";
        }

        return "application/octet-stream";
    }

    private static void loadSample(HttpExchange exchange) throws IOException {
        loadSampleModel();
        sendJson(exchange, modelAsJson());
    }

    private static void uploadRdf(HttpExchange exchange) throws IOException {
        String rdfXml = readBody(exchange);

        synchronized (model) {
            model.removeAll();
            RDFDataMgr.read(model, new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)), Lang.RDFXML);
        }

        sendJson(exchange, modelAsJson());
    }

    private static void addBook(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String title = jsonString(body, "title");
        String themes = jsonArrayAsCsv(body, "themes");
        String readingLevel = jsonString(body, "readingLevel");

        addOrUpdateBook(title, themes, readingLevel);
        saveModel();
        sendJson(exchange, modelAsJson());
    }

    private static void changeLevel(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String title = jsonString(body, "title");
        String readingLevel = jsonString(body, "readingLevel");
        Resource book = model.createResource(EX + toId(title));

        synchronized (model) {
            model.removeAll(book, SUITABLE_FOR, null);
            addReadingLevel(readingLevel);
            book.addProperty(SUITABLE_FOR, model.createResource(EX + toId(readingLevel)));
        }

        saveModel();
        sendJson(exchange, modelAsJson());
    }

    private static void sendRdfXml(HttpExchange exchange) throws IOException {
        send(exchange, 200, "application/rdf+xml", modelAsRdfXml());
    }

    private static void listBooks(HttpExchange exchange) throws IOException {
        String queryText = """
                PREFIX ex: <http://example.org/books#>
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

                SELECT ?book ?title ?level (GROUP_CONCAT(?themeName; separator=", ") AS ?themes)
                WHERE {
                    ?book rdf:type ex:Book .
                    OPTIONAL { ?book rdfs:label ?title . }
                    OPTIONAL { ?book ex:suitableFor ?level . }
                    OPTIONAL {
                        ?book ex:hasTheme ?theme .
                        BIND(STRAFTER(STR(?theme), "#") AS ?themeName)
                    }
                }
                GROUP BY ?book ?title ?level
                ORDER BY ?title ?book
                """;
        Query query = QueryFactory.create(queryText);
        StringBuilder json = new StringBuilder("{\"books\":[");
        boolean first = true;

        synchronized (model) {
            try (QueryExecution execution = QueryExecutionFactory.create(query, model)) {
                ResultSet results = execution.execSelect();

                while (results.hasNext()) {
                    var row = results.next();
                    String uri = row.getResource("book").getURI();
                    String title = row.contains("title") ? row.getLiteral("title").getString() : shortName(uri);
                    String level = row.contains("level") ? shortName(row.getResource("level").getURI()) : "";
                    String themes = row.contains("themes") ? row.getLiteral("themes").getString() : "";

                    if (!first) {
                        json.append(",");
                    }

                    json.append("{\"id\":\"").append(escapeJson(shortName(uri))).append("\",")
                            .append("\"uri\":\"").append(escapeJson(uri)).append("\",")
                            .append("\"title\":\"").append(escapeJson(title)).append("\",")
                            .append("\"readingLevel\":\"").append(escapeJson(level)).append("\",")
                            .append("\"themes\":\"").append(escapeJson(themes)).append("\"}");
                    first = false;
                }
            }
        }

        json.append("]}");
        sendJson(exchange, json.toString());
    }

    private static void chatStarters(HttpExchange exchange) throws IOException {
        String page = queryParameter(exchange, "page");
        String bookId = queryParameter(exchange, "bookId");
        List<String> starters = vectorStore.starters(page, bookId);
        StringBuilder json = new StringBuilder("{\"starters\":[");

        for (int i = 0; i < starters.size(); i++) {
            if (i > 0) {
                json.append(",");
            }

            json.append("\"").append(escapeJson(starters.get(i))).append("\"");
        }

        json.append("]}");
        sendJson(exchange, json.toString());
    }

    private static void chat(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String message = jsonString(body, "message");
        String page = jsonString(body, "page");
        String bookId = jsonString(body, "bookId");
        List<VectorResult> results = vectorStore.search(message + " " + bookId, 3);
        String context = vectorStore.context(results);
        String answer = askLocalLlm(message, context);

        if (answer.isBlank()) {
            answer = vectorStore.directAnswer(message, page, bookId, results);
        }

        if (answer.isBlank()) {
            answer = vectorStore.fallbackAnswer(message, page, bookId, results);
        }

        StringBuilder json = new StringBuilder("{\"answer\":\"");
        json.append(escapeJson(answer)).append("\",\"sources\":[");

        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                json.append(",");
            }

            json.append("\"").append(escapeJson(results.get(i).document.title)).append("\"");
        }

        json.append("]}");
        sendJson(exchange, json.toString());
    }

    private static void bookDetails(HttpExchange exchange) throws IOException {
        String id = queryParameter(exchange, "id");
        Resource book = model.createResource(EX + toId(id));
        String queryText = """
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX ex: <http://example.org/books#>

                SELECT ?title ?level ?theme
                WHERE {
                    OPTIONAL { <%s> rdfs:label ?title . }
                    OPTIONAL { <%s> ex:suitableFor ?level . }
                    OPTIONAL { <%s> ex:hasTheme ?theme . }
                }
                """.formatted(book.getURI(), book.getURI(), book.getURI());
        Query query = QueryFactory.create(queryText);
        String title = shortName(book.getURI());
        String level = "";
        StringBuilder themes = new StringBuilder();

        synchronized (model) {
            try (QueryExecution execution = QueryExecutionFactory.create(query, model)) {
                ResultSet results = execution.execSelect();

                while (results.hasNext()) {
                    var row = results.next();

                    if (row.contains("title")) {
                        title = row.getLiteral("title").getString();
                    }

                    if (row.contains("level")) {
                        level = shortName(row.getResource("level").getURI());
                    }

                    if (row.contains("theme")) {
                        if (!themes.isEmpty()) {
                            themes.append(", ");
                        }

                        themes.append(shortName(row.getResource("theme").getURI()));
                    }
                }
            }
        }

        StringBuilder json = new StringBuilder("{\"book\":{");
        json.append("\"id\":\"").append(escapeJson(shortName(book.getURI()))).append("\",")
                .append("\"uri\":\"").append(escapeJson(book.getURI())).append("\",")
                .append("\"title\":\"").append(escapeJson(title)).append("\",")
                .append("\"readingLevel\":\"").append(escapeJson(level)).append("\",")
                .append("\"themes\":\"").append(escapeJson(themes.toString())).append("\"},")
                .append("\"triples\":").append(bookTriplesJson(book)).append("}");
        sendJson(exchange, json.toString());
    }

    private static void addOrUpdateBook(String title, String themes, String readingLevel) {
        Resource book = model.createResource(EX + toId(title));

        synchronized (model) {
            book.addProperty(RDF.type, BOOK);
            model.removeAll(book, RDFS.label, null);
            model.removeAll(book, HAS_THEME, null);
            model.removeAll(book, SUITABLE_FOR, null);
            book.addProperty(RDFS.label, title);

            for (String theme : themes.split(",")) {
                String cleanTheme = theme.trim();

                if (!cleanTheme.isEmpty()) {
                    addTheme(cleanTheme);
                    book.addProperty(HAS_THEME, model.createResource(EX + toId(cleanTheme)));
                }
            }

            addReadingLevel(readingLevel);
            book.addProperty(SUITABLE_FOR, model.createResource(EX + toId(readingLevel)));
        }
    }

    private static void addTheme(String name) {
        model.createResource(EX + toId(name)).addProperty(RDF.type, THEME);
    }

    private static void addReadingLevel(String name) {
        model.createResource(EX + toId(name)).addProperty(RDF.type, READING_LEVEL);
    }

    private static String modelAsJson() {
        Query query = QueryFactory.create("SELECT ?s ?p ?o WHERE { ?s ?p ?o } ORDER BY ?s ?p ?o");
        StringBuilder json = new StringBuilder("{\"triples\":[");
        boolean first = true;

        synchronized (model) {
            try (QueryExecution execution = QueryExecutionFactory.create(query, model)) {
                ResultSet results = execution.execSelect();

                while (results.hasNext()) {
                    var row = results.next();

                    if (!first) {
                        json.append(",");
                    }

                    json.append("{\"subject\":\"").append(escapeJson(row.get("s").toString())).append("\",")
                            .append("\"predicate\":\"").append(escapeJson(row.get("p").toString())).append("\",")
                            .append("\"object\":\"").append(escapeJson(row.get("o").toString())).append("\",")
                            .append("\"objectType\":\"").append(row.get("o").isLiteral() ? "literal" : "resource").append("\"}");
                    first = false;
                }
            }
        }

        json.append("],\"rdfXml\":\"").append(escapeJson(modelAsRdfXml())).append("\"}");
        return json.toString();
    }

    private static String bookTriplesJson(Resource book) {
        String queryText = """
                SELECT ?p ?o
                WHERE { <%s> ?p ?o }
                ORDER BY ?p ?o
                """.formatted(book.getURI());
        Query query = QueryFactory.create(queryText);
        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        synchronized (model) {
            try (QueryExecution execution = QueryExecutionFactory.create(query, model)) {
                ResultSet results = execution.execSelect();

                while (results.hasNext()) {
                    var row = results.next();

                    if (!first) {
                        json.append(",");
                    }

                    json.append("{\"subject\":\"").append(escapeJson(book.getURI())).append("\",")
                            .append("\"predicate\":\"").append(escapeJson(row.get("p").toString())).append("\",")
                            .append("\"object\":\"").append(escapeJson(row.get("o").toString())).append("\",")
                            .append("\"objectType\":\"").append(row.get("o").isLiteral() ? "literal" : "resource").append("\"}");
                    first = false;
                }
            }
        }

        json.append("]");
        return json.toString();
    }

    private static String modelAsRdfXml() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        synchronized (model) {
            model.setNsPrefix("ex", EX);
            model.setNsPrefix("rdf", RDF.uri);
            model.setNsPrefix("rdfs", RDFS.uri);
            RDFDataMgr.write(output, model, RDFFormat.RDFXML_PRETTY);
        }

        return output.toString(StandardCharsets.UTF_8);
    }

    private static void loadSampleModel() {
        synchronized (model) {
            model.removeAll();
            try {
                RDFDataMgr.read(model, Files.newInputStream(Path.of("task1.rdf")), Lang.RDFXML);
            } catch (IOException error) {
                throw new RuntimeException("Could not read task1.rdf", error);
            }
        }
    }

    private static void saveModel() throws IOException {
        synchronized (model) {
            try (OutputStream output = Files.newOutputStream(Path.of("task3-updated.rdf"))) {
                RDFDataMgr.write(output, model, RDFFormat.RDFXML_PRETTY);
            }
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String jsonString(String json, String name) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String jsonArrayAsCsv(String json, String name) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return "";
        }

        return matcher.group(1).replace("\"", "").replace("\n", "").replace("\r", "");
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();

        if (query == null) {
            return "";
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);

            if (parts.length == 2 && URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }

        return "";
    }

    private static String shortName(String uri) {
        int hashIndex = uri.lastIndexOf("#");
        int slashIndex = uri.lastIndexOf("/");
        int index = Math.max(hashIndex, slashIndex);
        return index >= 0 ? uri.substring(index + 1) : uri;
    }

    private static String toId(String value) {
        StringBuilder id = new StringBuilder();

        for (String part : value.trim().split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                id.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
            }
        }

        return id.toString();
    }

    private static String askLocalLlm(String message, String context) {
        String modelId = localModelId();

        if (modelId.isBlank()) {
            return "";
        }

        String prompt = """
                Use only the context below to answer. If the context does not contain the answer,
                say that the database does not contain that information.

                Context:
                %s

                User question: %s
                """.formatted(context, message);
        String requestJson = """
                {
                  "model": "%s",
                  "messages": [
                    {"role": "system", "content": "You are a book recommendation chatbot. Answer briefly using only the retrieved database context."},
                    {"role": "user", "content": "%s"}
                  ],
                  "temperature": 0.2
                }
                """.formatted(escapeJson(modelId), escapeJson(prompt));

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:1234/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return unescapeJson(jsonString(response.body(), "content")).trim();
            }
        } catch (Exception ignored) {
            return "";
        }

        return "";
    }

    private static String localModelId() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:1234/v1/models"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return jsonString(response.body(), "id");
            }
        } catch (Exception ignored) {
            return "";
        }

        return "";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String unescapeJson(String value) {
        return value.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json", body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private interface Handler {
        void run(HttpExchange exchange) throws Exception;
    }

    private static class VectorStore {
        private final List<BookDocument> documents = new ArrayList<>();
        private final Map<String, UserProfile> users = new HashMap<>();

        void load(Path xmlPath) {
            documents.clear();
            users.clear();

            try (InputStream input = Files.newInputStream(xmlPath)) {
                Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
                NodeList userNodes = xml.getElementsByTagName("user");

                for (int i = 0; i < userNodes.getLength(); i++) {
                    Element user = (Element) userNodes.item(i);
                    users.put(user.getAttribute("id"), new UserProfile(
                            user.getAttribute("id"),
                            text(user, "preferredTheme"),
                            text(user, "readingLevel")
                    ));
                }

                NodeList bookNodes = xml.getElementsByTagName("book");

                for (int i = 0; i < bookNodes.getLength(); i++) {
                    Element book = (Element) bookNodes.item(i);
                    List<String> themes = texts(book, "theme");
                    BookDocument document = new BookDocument(
                            book.getAttribute("id"),
                            text(book, "title"),
                            text(book, "author"),
                            themes,
                            text(book, "readingLevel")
                    );
                    document.vector = vectorize(document.searchText());
                    documents.add(document);
                }

                saveVectorDatabase();
            } catch (Exception error) {
                throw new RuntimeException("Could not build vector database from chatbot-books.xml", error);
            }
        }

        List<String> starters(String page, String bookId) {
            BookDocument book = findById(bookId);

            if ("book".equals(page) && book != null) {
                return List.of(
                        "Who wrote " + book.title + "?",
                        "What genre or theme is " + book.title + "?",
                        "Is " + book.title + " suitable for Alice?"
                );
            }

            return List.of(
                    "What is a book that I am most likely to enjoy from this list?",
                    "Which books are Science Fiction?",
                    "What book has the author Frank Herbert and the theme Science Fiction?"
            );
        }

        List<VectorResult> search(String query, int limit) {
            Map<String, Double> queryVector = vectorize(query);
            List<VectorResult> results = new ArrayList<>();

            for (BookDocument document : documents) {
                double score = cosine(queryVector, document.vector);
                results.add(new VectorResult(document, score));
            }

            results.sort(Comparator.comparingDouble((VectorResult result) -> result.score).reversed());
            return results.subList(0, Math.min(limit, results.size()));
        }

        String context(List<VectorResult> results) {
            StringBuilder context = new StringBuilder();

            for (VectorResult result : results) {
                context.append("- ").append(result.document.searchText()).append("\n");
            }

            if (!users.isEmpty()) {
                context.append("Users:\n");

                for (UserProfile user : users.values()) {
                    context.append("- ").append(user.id)
                            .append(" prefers ").append(user.preferredTheme)
                            .append(" and has reading level ").append(user.readingLevel)
                            .append(".\n");
                }
            }

            return context.toString();
        }

        String fallbackAnswer(String message, String page, String bookId, List<VectorResult> results) {
            String normalized = normalize(message);

            if (normalized.contains("most likely") || normalized.contains("recommend") || normalized.contains("enjoy")) {
                return recommendationFor("Alice");
            }

            for (BookDocument document : documents) {
                if (normalized.contains(normalize(document.title)) && (normalized.contains("who wrote") || normalized.contains("author"))) {
                    return document.title + " was written by " + document.author + ".";
                }
            }

            List<BookDocument> matches = findByAuthorAndTheme(normalized);

            if (!matches.isEmpty()) {
                return titles(matches);
            }

            BookDocument currentBook = findById(bookId);

            if ("book".equals(page) && currentBook != null) {
                return currentBook.title + " was written by " + currentBook.author
                        + ". Themes: " + String.join(", ", currentBook.themes)
                        + ". Reading level: " + currentBook.readingLevel + ".";
            }

            return "I could not find an answer in the vector database.";
        }

        String directAnswer(String message, String page, String bookId, List<VectorResult> results) {
            String normalized = normalize(message);

            if (normalized.contains("most likely") || normalized.contains("recommend") || normalized.contains("enjoy")) {
                return "";
            }

            List<BookDocument> matches = findByAuthorAndTheme(normalized);

            if (!matches.isEmpty()) {
                return titles(matches);
            }

            for (BookDocument document : documents) {
                if (normalized.contains(normalize(document.title)) && (normalized.contains("who wrote") || normalized.contains("author"))) {
                    return document.title + " was written by " + document.author + ".";
                }
            }

            BookDocument currentBook = findById(bookId);

            if ("book".equals(page) && currentBook != null && normalized.contains("suitable")) {
                return currentBook.title + " is suitable for " + currentBook.readingLevel + " readers.";
            }

            for (UserProfile user : users.values()) {
                String userName = normalize(user.id);
                boolean asksPreference = normalized.contains("like")
                        || normalized.contains("prefer")
                        || normalized.contains("genre")
                        || normalized.contains("theme")
                        || normalized.contains("reading level");

                if (normalized.contains(userName) && asksPreference) {
                    return user.id + " prefers " + user.preferredTheme
                            + " and has reading level " + user.readingLevel + ".";
                }
            }

            return "";
        }

        private List<BookDocument> findByAuthorAndTheme(String normalizedQuestion) {
            List<BookDocument> matches = new ArrayList<>();

            for (BookDocument document : documents) {
                boolean authorMatches = normalizedQuestion.contains(normalize(document.author));
                boolean themeMatches = false;

                for (String theme : document.themes) {
                    if (normalizedQuestion.contains(normalize(theme))) {
                        themeMatches = true;
                    }
                }

                if (authorMatches && themeMatches) {
                    matches.add(document);
                }
            }

            return matches;
        }

        private String recommendationFor(String userId) {
            UserProfile user = users.get(userId);

            if (user == null) {
                return "I do not have user data for " + userId + ".";
            }

            List<BookDocument> exactMatches = new ArrayList<>();
            List<BookDocument> themeMatches = new ArrayList<>();

            for (BookDocument document : documents) {
                boolean sameTheme = document.hasTheme(user.preferredTheme);
                boolean sameLevel = document.readingLevel.equalsIgnoreCase(user.readingLevel);

                if (sameTheme && sameLevel) {
                    exactMatches.add(document);
                } else if (sameTheme) {
                    themeMatches.add(document);
                }
            }

            if (!exactMatches.isEmpty()) {
                return "For " + user.id + ", I recommend " + titles(exactMatches)
                        + " because it matches the preferred theme and reading level.";
            }

            if (!themeMatches.isEmpty()) {
                return "There is no exact theme-and-level match for " + user.id
                        + ", but the closest theme matches are " + titles(themeMatches) + ".";
            }

            return "I could not find a recommendation for " + user.id + " in the vector database.";
        }

        private BookDocument findById(String id) {
            String normalizedId = normalize(id);

            for (BookDocument document : documents) {
                if (normalize(document.id).equals(normalizedId) || normalize(document.title).equals(normalizedId)) {
                    return document;
                }
            }

            return null;
        }

        private String titles(List<BookDocument> books) {
            List<String> titles = new ArrayList<>();

            for (BookDocument book : books) {
                titles.add(book.title);
            }

            return String.join(", ", titles);
        }

        private void saveVectorDatabase() throws IOException {
            StringBuilder text = new StringBuilder();

            for (BookDocument document : documents) {
                text.append(document.title).append(" -> ").append(document.vector).append("\n");
            }

            Files.writeString(Path.of("vector_database.txt"), text.toString(), StandardCharsets.UTF_8);
        }

        private Map<String, Double> vectorize(String text) {
            Map<String, Double> vector = new HashMap<>();

            for (String token : tokens(text)) {
                vector.put(token, vector.getOrDefault(token, 0.0) + 1.0);
            }

            return vector;
        }

        private double cosine(Map<String, Double> a, Map<String, Double> b) {
            Set<String> words = new HashSet<>();
            words.addAll(a.keySet());
            words.addAll(b.keySet());
            double dot = 0;
            double aLength = 0;
            double bLength = 0;

            for (String word : words) {
                double av = a.getOrDefault(word, 0.0);
                double bv = b.getOrDefault(word, 0.0);
                dot += av * bv;
                aLength += av * av;
                bLength += bv * bv;
            }

            if (aLength == 0 || bLength == 0) {
                return 0;
            }

            return dot / (Math.sqrt(aLength) * Math.sqrt(bLength));
        }

        private List<String> tokens(String text) {
            String[] parts = normalize(text).split(" ");
            List<String> tokens = new ArrayList<>();

            for (String part : parts) {
                if (part.length() > 1) {
                    tokens.add(part);
                }
            }

            return tokens;
        }

        private String normalize(String text) {
            return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        }

        private String text(Element parent, String tagName) {
            NodeList nodes = parent.getElementsByTagName(tagName);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
        }

        private List<String> texts(Element parent, String tagName) {
            NodeList nodes = parent.getElementsByTagName(tagName);
            List<String> values = new ArrayList<>();

            for (int i = 0; i < nodes.getLength(); i++) {
                values.add(nodes.item(i).getTextContent().trim());
            }

            return values;
        }
    }

    private static class BookDocument {
        private final String id;
        private final String title;
        private final String author;
        private final List<String> themes;
        private final String readingLevel;
        private Map<String, Double> vector = new HashMap<>();

        BookDocument(String id, String title, String author, List<String> themes, String readingLevel) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.themes = themes;
            this.readingLevel = readingLevel;
        }

        String searchText() {
            return "Title: " + title + ". Author: " + author + ". Themes: "
                    + String.join(", ", themes) + ". Reading level: " + readingLevel + ".";
        }

        boolean hasTheme(String theme) {
            for (String currentTheme : themes) {
                if (currentTheme.equalsIgnoreCase(theme)) {
                    return true;
                }
            }

            return false;
        }
    }

    private static class UserProfile {
        private final String id;
        private final String preferredTheme;
        private final String readingLevel;

        UserProfile(String id, String preferredTheme, String readingLevel) {
            this.id = id;
            this.preferredTheme = preferredTheme;
            this.readingLevel = readingLevel;
        }
    }

    private static class VectorResult {
        private final BookDocument document;
        private final double score;

        VectorResult(BookDocument document, double score) {
            this.document = document;
            this.score = score;
        }
    }
}
