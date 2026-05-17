package de.melnichuk.ssi

import jakarta.servlet.Filter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.util.concurrent.TimeUnit

@SpringBootApplication
class ExampleApplication

fun main(args: Array<String>) {
    runApplication<ExampleApplication>(*args)
}

@Configuration
class Configuration {

    @Bean
    fun ssiFilter(): Filter = SSIFilter()
}

@Controller
class ExampleController {

    @ResponseBody
    @GetMapping
    fun home() = /* language=HTML */ """
        <html lang="en">
        <body>
        <!--#include virtual="http://localhost:8080/header" -->
        <p>Hello world 🤗</p>
        <!--#include virtual="http://localhost:8080/footer" -->
        </body>
        </html>
    """.trimIndent()

    @ResponseBody
    @GetMapping("/header")
    fun header() = /* language=HTML */ """<p>Here comes the <b>header</b></p>"""

    @ResponseBody
    @GetMapping("/footer")
    fun footer(): String {
        TimeUnit.SECONDS.sleep(3)

        return /* language=HTML */ """<p>Here comes the <b>footer</b></p>"""
    }
}