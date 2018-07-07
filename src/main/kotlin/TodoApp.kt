import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.content.default
import io.ktor.content.defaultResource
import io.ktor.content.resources
import io.ktor.content.static
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.Parameters
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.consumeEach
import java.util.Collections.synchronizedList


data class Todo(
    var id: Int,
    val name: String,
    val description: String,
    val completed: Boolean
)

sealed class TodoEvent()
data class AddedTodo(val todo: Todo) : TodoEvent()
data class RemovedTodo(val todo: Todo) : TodoEvent()

fun Application.todoApp() {
    val todoList = mutableListOf<Todo>();
    val notifiers = synchronizedList(mutableListOf<SendChannel<TodoEvent>>())

    install(Routing)
    install(WebSockets)
    install(ContentNegotiation) {
        gson {
            setPrettyPrinting()
        }
    }

    routing {
        static("/") {
            defaultResource("web/todo.html")
            resources("web")
        }
        route("/todo") {
            get {
                call.respond(todoList);
            }
            get("/{id}") {
                call.respond(todoList[call.parameters.id]);
            }
            post {
                val todo = call.receive<Todo>();
                todo.id = todoList.size;
                todoList.add(todo);
                notifiers.broadcastAll(AddedTodo(todo))
                call.respond(todo)

            }
            delete("/{id}") {
                val id = call.parameters.id
                val todo = todoList.removeAt(id)
                notifiers.broadcastAll(RemovedTodo(todo))
                call.respond(todo);
            }
        }

        webSocket("/notifications") {
            val eventSender = createEventSender()

            notifiers.add(eventSender)
            try {
                incoming.consumeEach { }
            } finally {
                notifiers.remove(eventSender)
                eventSender.close()
            }
        }
    }
}

private fun WebSocketSession.createEventSender(): SendChannel<TodoEvent> {
    return actor<TodoEvent> {
        for (msg in channel) {
            outgoing.send(Frame.Text(msg.toString()))
        }
    }
}

private suspend fun MutableList<SendChannel<TodoEvent>>.broadcastAll(
    event: TodoEvent
) {
    val copyOfNotifiers = synchronized(this) {
        toList()
    }

    copyOfNotifiers.forEach {
        try {
            it.send(event)
        } catch (ex: CancellationException) {
            remove(it)
        }
    }

}

val Parameters.id: Int
    get() = this.get("id")?.toIntOrNull() ?: throw RuntimeException("bad param 'id'")

val server = embeddedServer(Netty, 8080, module = Application::todoApp)

fun main(args: Array<String>) {
    server.start(wait = true)
}
