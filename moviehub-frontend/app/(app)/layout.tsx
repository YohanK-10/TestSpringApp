import Navbar from "@/components/Navbar";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen text-white">
      <Navbar />
      <main className="pb-16">{children}</main>
    </div>
  );
}
