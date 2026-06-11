package com.stasao.gcam

enum class CheckStatus { OK, WARN, FAIL, INFO }

data class CheckResult(
    val title: String,
    val status: CheckStatus,
    val detail: String
)
