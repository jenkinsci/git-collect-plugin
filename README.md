# Git Collect Plugin for Jenkins

[![Jenkins Plugin](https://img.shields.io/badge/jenkins-plugin-blue.svg)](https://jenkins.io)
[![Build Status](https://img.shields.io/badge/build-ready-brightgreen.svg)]()

**Git Collect** is a Jenkins plugin that allows you to register an **already existing** local Git repository into the Jenkins build data without performing a network `fetch`, `clone`, or `checkout`.

This is ideal for complex pipelines where the source code is retrieved by external scripts, pre-mounted volumes, or other tools, but you still want Jenkins to record the Git revision, branch information, and trigger downstream plugins (like the Git Parameter or Release plugins).

---

## 🚀 Features

* **No Network Activity:** Strictly operates on the local file system.
* **Pipeline Support:** First-class support for Jenkins Pipeline via `collectGit`.
* **Accurate Revision Tracking:** Distinguishes between the **User Intent** (Marked Revision, e.g., `origin/master`) and the **Actual Result** (Built Revision, e.g., `SHA1`).
* **Compatibility:** Generates standard `BuildData` actions, making it compatible with other plugins that rely on the standard Git plugin data structures.

---

## 🛠 Usage

### 1. Jenkins Pipeline (Jenkinsfile)

The plugin exposes the `collectGit` step.

#### Basic Usage
If you just want to register a repo found in the workspace:
```groovy
pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                // Assume code is downloaded here by a script/tool
                sh './download_code.sh'
            }
        }
        stage('Register Git Data') {
            steps {
                // Looks for .git in the 'src' folder
                collectGit path: 'src'
            }
        }
    }
}
