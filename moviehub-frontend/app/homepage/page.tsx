"use client"

export default function landingPage() {
    const userName = "Yohan"; // Replace with dynamic data

    return (
        <div className="min-h-screen flex bg-black text-white">
            {/* Sidebar */}
            <aside className="w-60 bg-gray-900 p-4 flex flex-col space-y-4">
                <div className="text-amber-500 font-bold text-xl mb-8">YourApp</div>
                <nav className="flex flex-col space-y-3">
                    <a href="/dashboard" className="hover:text-amber-400">Dashboard</a>
                    <a href="/profile" className="hover:text-amber-400">Profile</a>
                    <a href="/settings" className="hover:text-amber-400">Settings</a>
                    <a href="/logout" className="hover:text-amber-400">Logout</a>
                </nav>
            </aside>

            {/* Main content */}
            <main className="flex-1 p-8">
                <h1 className="text-3xl font-bold mb-6">Welcome back, {userName}!</h1>

                <section className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <div className="bg-gray-800 p-6 rounded-lg shadow-lg">
                        <h2 className="text-xl font-semibold mb-2">Recent Activity</h2>
                        {/* Replace with actual recent activity */}
                        <p>No recent activity</p>
                    </div>

                    <div className="bg-gray-800 p-6 rounded-lg shadow-lg">
                        <h2 className="text-xl font-semibold mb-2">Quick Actions</h2>
                        <button className="bg-amber-600 px-4 py-2 rounded hover:bg-amber-500 transition">
                            Start New Task
                        </button>
                    </div>

                    <div className="bg-gray-800 p-6 rounded-lg shadow-lg">
                        <h2 className="text-xl font-semibold mb-2">Notifications</h2>
                        <p>No new notifications</p>
                    </div>
                </section>
            </main>
        </div>
    );
}