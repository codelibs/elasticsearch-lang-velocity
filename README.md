Elasticsearch Velocity Lang Plugin
=======================

## Overview

This plugin add Velocity language to Elasticsearch.

## Version

| Version   | Elasticsearch |
|:---------:|:-------------:|
| master    | 1.4.X         |

### Issues/Questions

Please file an [issue](https://github.com/codelibs/elasticsearch-lang-velocity/issues "issue").
(Japanese forum is [here](https://github.com/codelibs/codelibs-ja-forum "here").)

## Installation

### Install Velocity Language Plugin

TBD

    $ $ES_HOME/bin/plugin --install org.codelibs/elasticsearch-lang-velocity/1.4.0

## References

This plugin supports an executable script language(search script is not supported).

### Using on Script-based Search Template

Using [Script-based Search Template](https://github.com/codelibs/elasticsearch-sstmpl "Script-based Search Template") Plugin, you can search by Velocity template.

    GET /_search/template
    {
        "lang": "velocity",
        "template": "{\"query\": {\"match\": {\"title\": \"${query_string}\"}}}",
        "params": {
            "query_string": "search for these words"
        }
    }

### Where is a root directory for Velocity's template

The directory is ${es.conf}/scripts.
The file extension for Velocity's template is .vm or .velocity.

### Use Template Cache

To use a template cache for Velocity template, please prepend "##cache" to the template file.
