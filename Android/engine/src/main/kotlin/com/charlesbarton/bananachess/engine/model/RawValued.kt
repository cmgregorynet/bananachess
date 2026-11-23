package com.charlesbarton.bananachess.engine.model

/** An enum whose cases have the stable string names the Swift engine writes into payloads and fixtures. */
interface RawValued {
    val rawValue: String
}

/** The case with this raw value, or null when there is none. */
inline fun <reified E> rawValueOf(rawValue: String): E? where E : Enum<E>, E : RawValued =
    enumValues<E>().firstOrNull { it.rawValue == rawValue }
