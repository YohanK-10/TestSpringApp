/** @type {import('next').NextConfig} */
const nextConfig = {
  eslint: {
    // `next lint --no-cache` remains the dedicated lint path; skipping build-time lint
    // avoids `.next` cache file issues in this local Windows environment.
    ignoreDuringBuilds: true,
  },
};

export default nextConfig;
