Elasticsearch Velocity Lang Plugin
=======================

## Overview

This plugin add Velocity language to Elasticsearch.

## Version

[Versions in Maven Repository](https://repo1.maven.org/maven2/org/codelibs/elasticsearch-lang-velocity/)

### Issues/Questions

Please file an [issue](https://github.com/codelibs/elasticsearch-lang-velocity/issues "issue").

## Installation

    $ $ES_HOME/bin/elasticsearch-plugin install org.codelibs:elasticsearch-lang-velocity:7.6.0

## References

This plugin supports an executable script language(search script is not supported).

### Using on Script-based Search Template

Using [Script-based Search Template](https://github.com/codelibs/elasticsearch-sstmpl "Script-based Search Template") Plugin, you can search by Velocity template.

    GET /_search/script_template
    {
        "lang": "velocity",
        "inline": "{\"query\": {\"match\": {\"title\": \"${query_string}\"}}}",
        "params": {
            "query_string": "search for these words"
        }
    }

### Where is a root directory for Velocity's template

The directory is ${es.config}/scripts.
The file extension for Velocity's template is .vm or .velocity.

### Use Template Cache

To use a template cache for Velocity template, please prepend "##cache" to the template file.

