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
      external: [],
    },
  },
  resolve: {
    alias: [
      { find: '@casehubio/pages-ui-components/split-workbench', replacement: resolve(__dirname, '.casehub-packages/packages/pages-ui-components/dist/split-workbench') },
      { find: '@casehubio/mosaic-detail-pane', replacement: resolve(__dirname, '.casehub-packages/packages/detail-pane/dist') },
      { find: '@casehubio/mosaic-channel-activity', replacement: resolve(__dirname, '.casehub-packages/packages/channel-activity/dist') },
      { find: '@casehubio/mosaic-core', replacement: resolve(__dirname, '.casehub-packages/packages/mosaic-core/dist') },
      { find: '@casehubio/pages-component', replacement: resolve(__dirname, 'shims/pages-component') },
      { find: '@casehubio/pages-primitives', replacement: resolve(__dirname, '.casehub-packages/packages/pages-primitives/dist') },
      { find: /^@casehubio\/pages-data\/dist\/(.*)/, replacement: resolve(__dirname, '.casehub-packages/packages/pages-data/dist/$1') },
      { find: '@casehubio/pages-data', replacement: resolve(__dirname, '.casehub-packages/packages/pages-data/dist') },
      { find: '@casehubio/pages-table', replacement: resolve(__dirname, '.casehub-packages/packages/pages-table/dist') },
      { find: /^@casehubio\/pages-ui-components.*/, replacement: resolve(__dirname, 'shims/pages-ui-components') },
      { find: '@casehubio/pages-filter-bar', replacement: resolve(__dirname, 'shims/pages-filter-bar') },
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
