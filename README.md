Elasticsearch Velocity Lang Plugin
=======================

## Overview

This plugin add Velocity language to Elasticsearch.

## Version

| Version   | Elasticsearch |
|:---------:|:-------------:|
| master    | 2.3.X         |
| 2.3.2     | 2.3.5         |
| 2.2.0     | 2.2.2         |
| 2.1.0     | 2.1.1         |
| 1.5.0     | 1.5.1         |
| 1.4.0     | 1.4.0         |

### Issues/Questions

Please file an [issue](https://github.com/codelibs/elasticsearch-lang-velocity/issues "issue").
(Japanese forum is [here](https://github.com/codelibs/codelibs-ja-forum "here").)

## Installation

### Install Velocity Language Plugin

    $ $ES_HOME/bin/plugin install org.codelibs/elasticsearch-lang-velocity/2.3.2

### Enable Dynamic Scripting

To use this plugin, enable dynamic scripting.
For more information, see [enabling dynamic scripting](https://www.elastic.co/guide/en/elasticsearch/reference/current/modules-scripting.html#enable-dynamic-scripting "enabling dynamic scripting").

    script.inline: true
    script.indexed: true
    script.file: true
    script.search: true



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

If you use a template file, please put template\_name.vm into ${es.confing}/scripts and send the following query:

    GET /_search/template
    {
        "lang": "velocity",
        "template": {"file":"template_name"},
        "params": {
            "query_string": "search for these words"
        }
    }

### Where is a root directory for Velocity's template

The directory is ${es.config}/scripts.
The file extension for Velocity's template is .vm or .velocity.

### Use Template Cache

To use a template cache for Velocity template, please prepend "##cache" to the template file.
