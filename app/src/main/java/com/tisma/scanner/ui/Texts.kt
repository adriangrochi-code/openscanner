package com.tisma.scanner.ui

/** «1 foto», «2 fotos», «0 fotos»: un número con su palabra en singular o plural. */
fun count(n: Int, one: String, many: String): String = "$n " + if (n == 1) one else many
