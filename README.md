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

The same revision of a repository is collected into the build once, whichever branch of a `parallel`
stage reaches the same checkout first: a later `collectGit` reads the same history and registers
nothing, so that it is not listed twice in the build. Two collects of one repository at two different
revisions are both recorded. The step says so in the build log when it skips.

A collection that brought no new commit - `markedCommit` resolving to the revision that is checked
out - is registered like any other: the changelog is empty and the build shows no changes, but the
checkout is recorded, and the repository is listed in the build as it is for a checkout that brought
commits.

#### Changelog links and the repository browser

With `changelog: true`, every commit of the changelog links to its page on the web frontend of the
repository, and every changed file links to its diff. The plugin works the address out from the remote
URL and appends the commit part itself, so this is only needed for the repositories it cannot recognise.

There are two ways to say which browser to use, one terse and one complete:

* `browserUrl` is the base URL of the repository browser, without the `+/<sha1>` part. The link format
  still follows the URL, so it reaches the frontends whose links can be told apart by their address.
* `browser` is a whole repository browser, the same ones the Git SCM offers, and says both the address
  and the link format. It is what the **Repository Browser** drop down sets in the job configuration.

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

Any other browser can be named outright, for a frontend the plugin would not have recognised or for one
whose links it would have built the wrong way:

```groovy
collectGit path: 'src', changelog: true,
           browser: [$class: 'Gitiles', repoUrl: 'https://gerrit.example.com/plugins/gitiles/amarula/checks-jenkins']

// or any other browser the Git SCM plugin offers, e.g.:
collectGit path: 'src', changelog: true,
           browser: [$class: 'GitLab', repoUrl: 'https://gitlab.example.com/amarula/checks-jenkins/']
```

Left unset, the address is guessed from the remote URL:

* a `github.com`, `gitlab.com` or `bitbucket.org` remote gets its own frontend;
* a remote shaped like a Gerrit - its host name, an `/a/` clone path, a `plugins/gitiles` path, or the
  SSH port 29418 - is linked with the gitiles URL format, as in
  `https://gerrithub.io/plugins/gitiles/amarula/checks-jenkins/+/<sha1>%5E%21`;
* anything else is linked with the GitHub URL format, as in `.../commit/<sha1>`.

That guess cannot know about a Gerrit served from a name that says nothing about it and cloned over an
anonymous https URL, nor about a self-hosted frontend: `browserUrl` and `browser` are what cover those.
It reads a little too much as well, since Gerrit's `/a/` clone path is the same on the wire as a project
that lives in an `a` directory, and such a remote is linked as a Gerrit until told otherwise.

`browserUrl` sets the address but not the link format - the plugin still reads the Gerrit markers off
the URL to choose between the gitiles and the GitHub forms - so where the format matters, name the
browser instead.
