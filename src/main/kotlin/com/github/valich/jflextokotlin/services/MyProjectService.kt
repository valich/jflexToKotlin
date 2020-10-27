package com.github.valich.jflextokotlin.services

import com.intellij.openapi.project.Project
import com.github.valich.jflextokotlin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
