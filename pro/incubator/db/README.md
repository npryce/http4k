# http4k Transactional Postbox

This module provides a simple mechanism to introduce async processing for HTTP messages.

The most common use-case for this mechanism is the implementation of the [Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html) pattern to prevent data inconsistencies and bugs when sending messages between services or external systems.

## Getting started

### Installation (Gradle)

```kotlin
dependencies {
    implementation(platform("org.http4k:http4k-bom:5.46.0.0"))
    implementation("org.http4k:http4k-connect-transactional-postbox")
}
```

## Usage

### Postbox as an HttpHandler

The main application of a Postbox is to use it as an `HttpHandler` to intercept requests (either incoming or outgoing) in your application. 

For instance, if you have an existing adapter such as:

```kotlin
class SmsNotificationClient(val client: HttpHandler){
    fun sendSms(messageId: String, destination: String, message: String) {
        client(Request(POST, "/sms")
            .header("x-message-id", messageId)
            .header("destination", destination)
            .body(message))
    }
}
```
You then have options to replace the client with a transactional outbox to process the message asynchronously:

Before:
```kotlin
val client = SetBaseUriFrom(Uri.of("https://sms-service.external").then(OkHttp())
val smsClient = SmsNotificationClient(client)
```

After:
```kotlin
val outbox: PostboxTransactor = ...
val smsClient = SmsNotificationClient(outbox.intercepting(fromHeader("x-message-id")))
```

### Transactional storage

The Postbox requires a transactional storage to keep and process requests reliably. For that, we use a `Transactor` to manage the transaction lifecycle.

Here's an example of how to create a `Transactor` for a PostgreSQL database managed with [Exposed](https://jetbrains.github.io/Exposed/home.html):

```kotlin
val datasource = HikariDataSource(HikariConfig().apply {
        driverClassName = "org.postgresql.Driver"
        username = "postgres"
        password = "mysecretpassword"
        jdbcUrl = "jdbc:postgresql://localhost:5432/postgres"
    })

val transactor = ExposedTransactor(datasource, ::ExposedPostbox)
```

The currently supported transactors are:

* In-memory - for testing purposes, manages the transaction by using simple locks
* Exposed - for SQL databases, manages the transaction using Exposed
* Datasource - for SQL databases, manages the transaction using JDBC DataSource and expects databases to support [Serializable isolation level](https://en.wikipedia.org/wiki/Isolation_(database_systems)#Serializable)

### Idempotency

Idempotency for the Postbox is achieved by having a deterministic `requestId` for each received request.

Out-of-the-box options are:

* Header - uses a header value to identify the request
* Path - uses a path parameter to identify the request

Alternatively, you can customise this by implementing the `RequestIdResolver` function, like this:

```kotlin
val myResolver: RequestIdResolver = { request: Request ->
    request.header("X-Request-Id") ?: error("No request ID found")
}
```
In this example, the `X-Request-Id` header is used to identify the request and clients are expect to send that header for each request.

This resolver can then be passed to the Postbox interceptor:

```kotlin
val postbox: PostboxTransactor = ...
routes("/sms" bind POST to postbox.intercepting(myResolver))
```

### Filtering request/response data

When storing requests, you may want to remove sensitive data or manipulate the request/response data. 

This can be achieved using any http4k `Filter`. For instance, using one of the built-in filters:

```kotlin
val outbox: PostboxTransactor = ...
val outboxClient = RequestFilters.ExcludeHeaders("Authorization")
    .then(outbox.intercepting(fromHeader("x-message-id")))

val smsClient = SmsNotificationClient(outboxClient)
```

### Background Processing

The Postbox provides a simple mechanism to process the requests in the background. Here's an example:

```kotlin

val myRequestHandler: HttpHandler = { request -> // this is the request stored in the postbox
    val success: Boolean = // result of processing the request 
    if(success) Response(OK) else Response(INTERNAL_SERVER_ERROR) // indicates if the request was processed successfully
}

val postbox: PostboxTransactor = ...
    
PostboxProcessing(transactor, myRequestHandler).start()
```

This will start a single background (virtual) thread to process the requests in the Postbox using polling.

It'll do so by periodically processing a small bach of pending requests in a single database transaction. 

The responses for those requests are stored so they can be consumed or served later.

By default, a request will be marked as processed if the handler returns a `2xx` status code. Otherwise, it'll be marked as failed and reprocessed later using an incremental backoff strategy. If it exceeds the maximum retry attempts, the request is marked as "dead" and won't be reprocessed. 

You can configure `PostboxProcessing` by providing the following options:

| Option | Description                                                                               | Default                                             |
|--------|-------------------------------------------------------------------------------------------|-----------------------------------------------------|
| `batchSize` | The number of requests to process in a single batch                                       | 10                                                  |
| `maxPollingTime` | The maximum time to wait between polling requests                                         | 5 seconds                                           |
| `successCriteria` | A `(Response) -> Boolean` function to determine if the request was processed successfully | `response.status.successful` (i.e. status code 2xx) |
| `maxFailures` | The maximum number of failures before marking the request as "dead"                       | 3                                                   |
| `backoffStrategy` | A function to calculate the delay before trying to reprocess a request again              | (((# failures) ^ 2) * 5 seconds) + random(10) seconds |


### Configuring response for pending requests

By default, the Postbox will return a `202 Accepted` response for requests that are still pending. 

Out-of-the-box options are:

* Empty - returns a `202 Accepted` response with no body
* Link - returns a `202 Accepted` response with a `Link` header pointing to the status endpoint
* Redirect - returns a `303 See Other` response with a `Location` header pointing to the status endpoint

Alternatively, you can customize this response by providing a custom `PendingResponseGenerator` for the Postbox:

```kotlin
val myCustomResult = Response(ACCEPTED).body("Your request is being processed. Please check back later")

val postbox = PostboxHandlers(transactor, myCustomResult)
```

### Checking the status of postbox requests

The Postbox provides a separate `HttpHandler` to check the status or retrieve the response of processed requests. 

Here's an example:

```kotlin
val postbox: PostboxTransactor = ...
val handlers = PostboxHandlers(transactor)

routes("/status/{requestId}" bind GET to handlers.status(fromPath("requestId")))
```

### Transactional Inbox

The Postbox can also be used to capture requests and serve the responses after processing them. Here's an example:

```kotlin
val transactor: PostboxTransactor = ...
val handler: HttpHandler = ...// a handler to process the requests in the background

PostboxProcessing(transactor, handler).start()

val inbox = PostboxHandlers(
    transactor, 
    redirect("taskId", from("http://localhost:9000/workload/status/{taskId}")) // redirect pending requests to the status endpoint
)

routes(
    "/workload/submit/{taskId}" bind POST to inbox.intercepting(fromPath("taskId")),
    "/workload/status/{taskId}" bind GET to inbox.status(fromPath("taskId"))
).asServer(SunHttp(9000)).start()
```

## Testing

## Developing

### Starting PostgreSQL for testing

```shell
docker run --name http4k-test-postgres -p 5432:5432 -e POSTGRES_PASSWORD=mysecretpassword -d postgres:17.2
```

### Starting MySQL for testing

```shell
docker run --name http4k-test-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=mysecretpassword -d mysql:9.2
```
