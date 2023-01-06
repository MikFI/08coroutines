package ru.netology.coroutines

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import ru.netology.coroutines.dto.Author
import ru.netology.coroutines.dto.Comment
import ru.netology.coroutines.dto.Post
import ru.netology.coroutines.dto.PostWithComments
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/*
fun main() {
    runBlocking {
        println(Thread.currentThread().name)
    }
}
*/

/*
fun main() {
    CoroutineScope(EmptyCoroutineContext).launch {
        println(Thread.currentThread().name)
    }

    Thread.sleep(1000L)
}
*/

/*
fun main() {
    val custom = Executors.newFixedThreadPool(64).asCoroutineDispatcher()
    with(CoroutineScope(EmptyCoroutineContext)) {
        launch(Dispatchers.Default) {
            println(Thread.currentThread().name)
        }
        launch(Dispatchers.IO) {
            println(Thread.currentThread().name)
        }
        // will throw exception without UI
        // launch(Dispatchers.Main) {
        //    println(Thread.currentThread().name)
        // }

        launch(custom) {
            println(Thread.currentThread().name)
        }
    }
    Thread.sleep(1000L)
    custom.close()
}
*/

/*
//в этом варианте мы грузим список постов, после чего по-очереди подгружаем комментарии
//к каждому из постов - ни о каком параллельном исполнении речи не идёт
private val gson = Gson()
private val BASE_URL = "http://127.0.0.1:9999"
private val client = OkHttpClient.Builder()
//    .addInterceptor(HttpLoggingInterceptor(::println).apply {
//        level = HttpLoggingInterceptor.Level.BODY
//    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

fun main() {
    with(CoroutineScope(EmptyCoroutineContext)) {
        launch {
            try {
                var curTime = System.currentTimeMillis()
                val tmp1 = getPosts(client)
                println("get posts took ${System.currentTimeMillis() - curTime} ms")
                curTime = System.currentTimeMillis()
                val posts = tmp1
                    .map { post ->
                        PostWithComments(post, getComments(client, post.id))
                    }
                println("get comments took ${System.currentTimeMillis() - curTime} ms")
                println(posts)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    Thread.sleep(30_000L)
}

suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(::newCall)
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
    }
}

suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        client.apiCall(url)
            .let { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw RuntimeException(response.message)
                }
                val body = response.body ?: throw RuntimeException("response body is null")
                gson.fromJson(body.string(), typeToken.type)
            }
    }

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest("$BASE_URL/api/slow/posts", client, object : TypeToken<List<Post>>() {})

suspend fun getComments(client: OkHttpClient, id: Long): List<Comment> =
    makeRequest("$BASE_URL/api/slow/posts/$id/comments", client, object : TypeToken<List<Comment>>() {})
*/


//в этом варианте мы грузим список постов, затем ставим в параллель загрузку
//комментариев к каждому из постов
//и в конце формируем список авторов постов + комментариев, после чего
//загружаем их так же параллельно
private val gson = Gson()
private val BASE_URL = "http://127.0.0.1:9999"
private val client = OkHttpClient.Builder()
//    .addInterceptor(HttpLoggingInterceptor(::println).apply {
//        level = HttpLoggingInterceptor.Level.BODY
//    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

fun main() {
    with(CoroutineScope(EmptyCoroutineContext)) {
        launch {
            try {
                var curTime = System.currentTimeMillis()
                val tmp1 = getPosts(client)
                println("get posts took ${System.currentTimeMillis() - curTime} ms")
                curTime = System.currentTimeMillis()
                val posts = tmp1
                    .map { post ->
                        async {
                            PostWithComments(post, getComments(client, post.id))
                        }
                    }.awaitAll()
                println("get comments took ${System.currentTimeMillis() - curTime} ms")
                curTime = System.currentTimeMillis()

                //получаем id всех авторов из постов/комментариев
                val authorIds = mutableListOf<Long>()
                posts.forEach {
                    if (it.post.authorId !in authorIds){
                        authorIds.add(it.post.authorId)
                    }
                    it.comments.forEach { comment ->
                        if (comment.authorId !in authorIds){
                            authorIds.add(comment.authorId)
                        }
                    }
                }

                //загружаем всех авторов по полученным id
                val authors = authorIds.map{authorId ->
                    async{
                        authorId to getAuthor(client, authorId).name
                    }
                }.awaitAll().toMap()
                println("get authors took ${System.currentTimeMillis() - curTime} ms")

                //выводим всё полученное безобразие в консоль
                posts.forEach { post ->
                    println("PostId = " + post.post.id)
                    println("PostAuthor = " + authors[post.post.authorId])
                    println("Post = " + post.post.content)
                    post.comments.forEach { comment ->
                        println("    CommentAuthor = " + authors[comment.authorId])
                        println("    Comment = " + comment.content)
                    }
                    println("=============")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    Thread.sleep(30_000L)
}

suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(::newCall)
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
    }
}

suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        client.apiCall(url)
            .let { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw RuntimeException(response.message)
                }
                val body = response.body ?: throw RuntimeException("response body is null")
                gson.fromJson(body.string(), typeToken.type)
            }
    }

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest("$BASE_URL/api/slow/posts", client, object : TypeToken<List<Post>>() {})

suspend fun getComments(client: OkHttpClient, id: Long): List<Comment> =
    makeRequest("$BASE_URL/api/slow/posts/$id/comments", client, object : TypeToken<List<Comment>>() {})

suspend fun getAuthor(client: OkHttpClient, id: Long): Author =
    makeRequest("$BASE_URL/api/slow/authors/$id", client, object : TypeToken<Author>() {})
