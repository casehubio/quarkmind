import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  root: __dirname,
  build: {
    outDir: 'dist',
    rollupOptions: {
      input: resolve(__dirname, 'workbench-entry.ts'),
      output: {
        entryFileNames: 'blocks/workbench-blocks.js',
      },
    },
  },
  resolve: {
    alias: [
      { find: '@casehubio/blocks-ui-split-workbench', replacement: resolve(__dirname, '.casehub-packages/packages/split-workbench/src') },
      { find: '@casehubio/blocks-ui-detail-pane', replacement: resolve(__dirname, '.casehub-packages/packages/detail-pane/src') },
      { find: '@casehubio/blocks-ui-channel-activity', replacement: resolve(__dirname, '.casehub-packages/packages/channel-activity/src') },
      { find: '@casehubio/blocks-ui-core', replacement: resolve(__dirname, '.casehub-packages/packages/blocks-ui-core/src') },
      { find: '@casehubio/pages-component', replacement: resolve(__dirname, '.casehub-packages/packages/pages-component/dist') },
      { find: '@casehubio/pages-primitives', replacement: resolve(__dirname, '.casehub-packages/packages/pages-primitives/src') },
      { find: /^@casehubio\/pages-data\/dist\/(.*)/, replacement: resolve(__dirname, '.casehub-packages/packages/pages-data/src/$1') },
      { find: '@casehubio/pages-data', replacement: resolve(__dirname, '.casehub-packages/packages/pages-data/src') },
      { find: '@casehubio/pages-table', replacement: resolve(__dirname, '.casehub-packages/packages/pages-table/src') },
    ],
  },
  esbuild: {
    target: 'es2022',
    tsconfigRaw: JSON.stringify({
      compilerOptions: {
        experimentalDecorators: true,
        useDefineForClassFields: false,
      },
    }),
  },
});
