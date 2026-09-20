<p align="center">
  <img src="images/git-collect.png" width="800" alt="Git Collect Plugin">
</p>

# Git Collect Plugin for Jenkins

[![Jenkins Plugin](https://img.shields.io/badge/jenkins-plugin-blue.svg)](https://jenkins.io)
[![Build Status](https://img.shields.io/badge/build-ready-brightgreen.svg)]()

**Git Collect** is a Jenkins plugin that allows you to register an **already existing** local Git repository into the Jenkins build
data without performing a network `fetch`, `clone`, or `checkout`.

This is ideal for complex pipelines where the source code is retrieved by external scripts, pre-mounted volumes, or other tools, but you still
want Jenkins to record the Git revision, branch information, and be compatible as plugin like git-forensic.

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
        stage('Register Git Data from a marked commit') {
            steps {
                // Looks for .git in the 'src' folder, and reference commit 'markedCommit'
                collectGit path: 'src', markedCommit: '64ed978d54d2db4522a326c3f5cba6f8d4b41f8f'
            }
        }
        stage('Register Git Data from a marked commit, including changelog') {
            steps {
                // Looks for .git in the 'src' folder, and reference commit 'markedCommit'
                collectGit path: 'src', markedCommit: '64ed978d54d2db4522a326c3f5cba6f8d4b41f8f', changelog: true
            }
        }
    }
}
```

#### Changelog links and `browserUrl`

With `changelog: true`, every commit of the changelog links to its page on the web frontend of the
repository, and every changed file links to its diff. The plugin works the address out from the remote
URL and appends the commit part itself, so this is only needed for the repositories it cannot recognise.

`browserUrl` is that address: the base URL of the repository browser, without the `+/<sha1>` part.

GerritHub does not run the gitiles plugin, so its commit pages are not below `/plugins/gitiles/<project>`
but below `/c/<project>`, and the address guessed from the remote would answer 404:

```groovy
stage('Register Git Data, linking to GerritHub') {
    steps {
        collectGit path: 'src',
                   markedCommit: 'origin/master',
                   changelog: true,
                   // Commit links then read
                   // https://gerrithub.io/c/amarula/checks-jenkins/+/<sha1>%5E%21
                   browserUrl: 'https://gerrithub.io/c/amarula/checks-jenkins'
    }
}
```

The commit links and the `(diff)` links are then Gerrit routes, but the file-name links still use the
gitiles `+blame/<sha1>/<path>` form, which only a gitiles frontend serves: on GerritHub those lead
nowhere.

A Gerrit that does run the gitiles plugin is browsed below its plugin path instead, and serves all
three:

```groovy
collectGit path: 'src', changelog: true,
           browserUrl: 'https://gerrit.example.com/plugins/gitiles/amarula/checks-jenkins'
```

The same value can be set as **Browser URL** in the job configuration.

Left unset, the address is guessed from the remote URL:

* a `github.com`, `gitlab.com` or `bitbucket.org` remote gets its own frontend;
* a remote shaped like a Gerrit - its host name, an `/a/` clone path, a `plugins/gitiles` path, or the
  SSH port 29418 - is linked with the gitiles URL format, as in
  `https://gerrithub.io/plugins/gitiles/amarula/checks-jenkins/+/<sha1>%5E%21`;
* anything else is linked with the GitHub URL format, as in `.../commit/<sha1>`.

That guess cannot know about a Gerrit served from a name that says nothing about it and cloned over an
anonymous https URL, nor about a self-hosted frontend, which is what `browserUrl` is for. It does not
change the link format on its own: the plugin still reads the Gerrit markers off the URL to choose
between the gitiles and the GitHub forms, so on a Gerrit with the gitiles plugin, spelling the browse
path is what gets the gitiles links.
