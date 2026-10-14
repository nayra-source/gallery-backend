package com.gallery

import com.gallery.db.DatabaseFactory
import com.gallery.routes.AdminBootstrap

/**
 * CLI command to seed an admin account into the database.
 * Usage: ./gradlew seedAdmin --args="username password"
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: ./gradlew seedAdmin --args=\"<username> <password>\"")
        return
    }

    val username = args[0].trim()
    val password = args[1].trim()

    if (username.isBlank() || password.isBlank()) {
        println("Error: Username and password must not be empty.")
        return
    }

    println("Initializing database connection...")
    DatabaseFactory.init()

    println("Seeding admin user '$username'...")
    AdminBootstrap.createIfMissing(username, password)
    println("Successfully seeded admin user '$username' if it did not exist.")
}
