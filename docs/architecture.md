# Architecture

- Analyze your code only on your local machine (The plugin will download Docker images and dependencies for analysis. Other than that, you should not see any other outbound traffic.)

```mermaid
block-beta
columns 5
  block:Host:2
    columns 1
    IDE
    block
      columns 1
      Source[("Source Code")]
      DockerVolume_project[("Volume for project\n(e.g., indexes, report.json)")]
      DockerVolume_shared[("Volume for shared\n(e.g., .gradle/caches)")]
    end
  end
  space
  block:Container:2
    columns 1
    inga_ui["Container\n<a href='https://github.com/seachicken/inga-ui'>inga-ui</a>"]
    inga["Container\n<a href='https://github.com/seachicken/inga'>inga</a>"]
  end

  IDE --> Source
  IDE -- "HTTP" --> inga_ui
  IDE -- "LSP" --> inga
  inga -- "bind mount" --> Source
  inga_ui --> DockerVolume_project
  inga --> DockerVolume_project
  inga --> DockerVolume_shared
  inga --> Source
```
