{
  local base = {
      local labsjdk8 = {name: 'oraclejdk', version: '8u231-jvmci-19.3-b04', platformspecific: true},

      jdk8: {
        downloads+: {
          JAVA_HOME: labsjdk8,
        },
      },

      gate:        {targets+: ['gate']},

      common: {
        packages+: {
          'pip:astroid': '==1.1.0',
          'pip:pylint': '==1.1.0',
          'pip:ninja_syntax': '==1.7.2',
        },
      },

      linux: self.common + {
        packages+: {
          binutils: '>=2.30',
          git: '>=1.8.3',
          gcc: '==8.3.0',
          'gcc-build-essentials': '==8.3.0', # GCC 4.9.0 fails on cluster
          make: '>=3.83',
          llvm: '==8.0.1',
          nodejs: '==8.9.4',
        },
        capabilities+: ['linux', 'amd64'],
      },

      eclipse: {
        downloads+: {
          ECLIPSE: {name: 'eclipse', version: '4.5.2.1', platformspecific: true},
        },
        environment+: {
          ECLIPSE_EXE: '$ECLIPSE/eclipse',
        },
      },

      jdt: {
        downloads+: {
          JDT: {name: 'ecj', version: '4.6.1', platformspecific: false},
        },
      },

      wabt: {
        downloads+: {
          WABT_DIR: {name: 'wabt', version: '1.0.12', platformspecific: true},
        },
      },

      emsdk: {
        downloads+: {
          EMSDK_DIR: {name: 'emsdk', version: '1.39.3', platformspecific: true},
        },
        environment+: {
          EMCC_DIR: '$EMSDK_DIR/fastcomp/emscripten/',
          NODE_DIR: '$EMSDK_DIR/node/12.9.1_64bit/',
        },
      },
  },

  local gate_cmd       = ['mx', '--strict-compliance', 'gate', '--strict-mode', '--tags', '${GATE_TAGS}'],
  local gate_cmd_jvmci = ['mx', '--strict-compliance', '--dynamicimports', '/compiler', '--jdk', 'jvmci', 'gate', '--strict-mode', '--tags', '${GATE_TAGS}'],

  local gate_graalwasm = {
    setup+: [
      ['cd', 'wasm'],
      ['mx', 'sversions'],
    ],
    run+: [
      gate_cmd,
    ],
    timelimit: '35:00',
  },

  local gate_graalwasm_jvmci = {
    setup+: [
      ['cd', 'wasm'],
      ['mx', 'sversions'],
    ],
    run+: [
      gate_cmd_jvmci
    ],
    timelimit: '35:00',
  },

  local gate_graalwasm_emsdk_jvmci = {
    setup+: [
      ['set-export', 'ROOT_DIR', ['pwd']],
      ['set-export', 'EM_CONFIG', '$ROOT_DIR/.emscripten-config'],
      ['cd', 'wasm'],
      [
        './generate_em_config',
        '$EM_CONFIG',
        '$EMSDK_DIR/myfastcomp/emscripten-fastcomp/bin/',
        '$EMSDK_DIR/myfastcomp/old-binaryen/',
        '$EMSDK_DIR/fastcomp/emscripten/',
        ['which', 'node'],
      ],
      ['mx', 'sversions'],
    ],
    run+: [
      gate_cmd_jvmci
    ],
    timelimit: '35:00',
  },

  local jdk8_gate_linux_wabt        = base.jdk8 + base.gate + base.linux + base.wabt,
  local jdk8_gate_linux_wabt_emsdk  = base.jdk8 + base.gate + base.linux + base.wabt + base.emsdk,
  local jdk8_gate_linux_eclipse_jdt = base.jdk8 + base.gate + base.linux + base.eclipse + base.jdt,

  builds: [
    jdk8_gate_linux_eclipse_jdt + gate_graalwasm             + {environment+: {GATE_TAGS: 'style,fullbuild'}}         + {name: 'gate-graalwasm-style-fullbuild-linux-amd64'},
    jdk8_gate_linux_wabt        + gate_graalwasm_jvmci       + {environment+: {GATE_TAGS: 'build,wasmtest'}}          + {name: 'gate-graalwasm-unittest-linux-amd64'},
    jdk8_gate_linux_wabt_emsdk  + gate_graalwasm_emsdk_jvmci + {environment+: {GATE_TAGS: 'buildall,wasmextratest'}}  + {name: 'gate-graalwasm-extra-unittest-linux-amd64'},
  ],
}